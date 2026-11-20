package com.bank.account.spending;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.AccountTransactionRepository;
import com.bank.account.TransactionType;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An amount of money out in a calendar month that the account's owner wants to hear about. When a
 * payment takes the month's money out over it, a SPENDING_ALERT_PASSED event is recorded and the
 * customer is emailed; once a month at most. It only tells: no payment is ever stopped by it. Months
 * are in the bank's time zone and count what the statements count as money out. Who may see or change
 * the account is checked by the caller.
 */
@Service
@Transactional
public class SpendingAlerts {

    public record SetAlert(@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    /** percent is whole, rounded down and at most 100. passed is true once spending is over the amount. */
    public record AlertResponse(Long accountId, BigDecimal amount, BigDecimal spentThisMonth, BigDecimal remaining,
            int percent, boolean passed) {
    }

    private final SpendingAlertRepository alerts;
    private final AccountRepository accounts;
    private final AccountTransactionRepository ledger;
    private final DomainEvents events;
    private final Clock clock;

    SpendingAlerts(SpendingAlertRepository alerts, AccountRepository accounts, AccountTransactionRepository ledger,
            DomainEvents events, @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.alerts = alerts;
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.clock = Clock.system(timeZone);
    }

    @Transactional(readOnly = true)
    public Optional<AlertResponse> find(Long accountId) {
        account(accountId);
        return alerts.findById(accountId).map(alert -> progress(accountId, alert.getAmount(), spentThisMonth(accountId)));
    }

    /** Sets the account's alert, or changes its amount. */
    public AlertResponse set(Long accountId, SetAlert request) {
        Account account = account(accountId);
        SpendingAlert alert = alerts.findById(accountId).orElseGet(() -> new SpendingAlert(accountId, clock.instant()));
        alert.set(request.amount().setScale(2), clock.instant());
        alerts.save(alert);
        events.record("SPENDING_ALERT_SET", accountId, Map.of("customerId", account.getCustomerId(),
                "alertAmount", alert.getAmount()));
        return progress(accountId, alert.getAmount(), spentThisMonth(accountId));
    }

    /** Removes the account's alert. Removing one it does not have is fine. */
    public void remove(Long accountId) {
        Account account = account(accountId);
        alerts.findById(accountId).ifPresent(alert -> {
            alerts.delete(alert);
            events.record("SPENDING_ALERT_REMOVED", accountId, Map.of("customerId", account.getCustomerId()));
        });
    }

    /**
     * Call after money has gone out of the account and its ledger entry is saved, in the same database
     * transaction. Payments from one account are one at a time (the account row's version), so two cannot
     * both send this month's email.
     */
    public void moneyWentOut(Account account) {
        alerts.findById(account.getId()).ifPresent(alert -> {
            LocalDate month = YearMonth.now(clock).atDay(1);
            if (month.equals(alert.getAlertedFor())) {
                return;
            }
            BigDecimal spent = spentThisMonth(account.getId());
            if (spent.compareTo(alert.getAmount()) <= 0) {
                return;
            }
            alert.alerted(month);
            String number = account.getAccountNumber();
            events.record("SPENDING_ALERT_PASSED", account.getId(), Map.of("customerId", account.getCustomerId(),
                    "accountEnding", number.substring(number.length() - 4), "amount", spent,
                    "alertAmount", alert.getAmount()));
        });
    }

    private BigDecimal spentThisMonth(Long accountId) {
        BigDecimal spent = ledger.sumOfTypesSince(accountId, TransactionType.MONEY_OUT,
                YearMonth.now(clock).atDay(1).atStartOfDay(clock.getZone()).toInstant());
        return spent == null ? BigDecimal.ZERO.setScale(2) : spent.setScale(2);
    }

    private Account account(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found."));
    }

    /** The sums, apart so they can be tested on their own. */
    static AlertResponse progress(Long accountId, BigDecimal amount, BigDecimal spent) {
        boolean passed = spent.compareTo(amount) > 0;
        int percent = passed ? 100
                : spent.multiply(BigDecimal.valueOf(100)).divide(amount, 0, RoundingMode.DOWN).intValue();
        BigDecimal remaining = amount.subtract(spent).max(BigDecimal.ZERO).setScale(2);
        return new AlertResponse(accountId, amount, spent, remaining, percent, passed);
    }
}
