package com.bank.account.savings;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.AccountTransaction;
import com.bank.account.AccountTransactionRepository;
import com.bank.account.AccountType;
import com.bank.account.TransactionType;
import com.bank.common.events.DomainEvents;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interest on savings accounts. It is worked out on each day's closing balance at a yearly rate, and
 * paid on the first of each month for the month before, from the day the account was opened. Days
 * start at midnight in the bank's time zone. Amounts under a cent are not paid, and part-cents are
 * dropped rather than rounded up.
 */
@Service
@Transactional
public class SavingsInterest {

    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Outcome {
        /** A new account was given its first payment day. */
        SCHEDULED,
        PAID,
        /** Due, but the balance earned less than a cent. */
        NOTHING_EARNED,
        NOT_DUE
    }

    private final AccountRepository accounts;
    private final AccountTransactionRepository ledger;
    private final DomainEvents events;
    private final BigDecimal annualRate;
    private final Clock clock;

    SavingsInterest(AccountRepository accounts, AccountTransactionRepository ledger, DomainEvents events,
            @Value("${bank.savings.annual-interest-rate}") BigDecimal annualRate,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.events = events;
        this.annualRate = annualRate;
        this.clock = Clock.system(timeZone);
    }

    /** Percent a year. */
    public BigDecimal annualRate() {
        return annualRate;
    }

    @Transactional(readOnly = true)
    public List<Long> dueIds() {
        return accounts.findSavingsIdsWithInterestDueBy(LocalDate.now(clock));
    }

    /**
     * Pays one month's interest if it is due. If the service was down over several first-of-months,
     * each run pays one month, so the account catches up an hour at a time.
     */
    public Outcome payIfDue(Long id) {
        LocalDate today = LocalDate.now(clock);
        Account account = accounts.findById(id).orElse(null);
        if (account == null || account.getType() != AccountType.SAVINGS) {
            return Outcome.NOT_DUE;
        }
        LocalDate opened = LocalDate.ofInstant(account.getCreatedAt(), clock.getZone());
        if (account.getNextInterestOn() == null) {
            account.nextInterestOn(YearMonth.from(opened).plusMonths(1).atDay(1));
            accounts.saveAndFlush(account);
            return Outcome.SCHEDULED;
        }
        LocalDate until = account.getNextInterestOn();
        if (until.isAfter(today)) {
            return Outcome.NOT_DUE;
        }
        LocalDate from = until.minusMonths(1).isBefore(opened) ? opened : until.minusMonths(1);
        BigDecimal interest = earned(account.getId(), from, until);
        account.nextInterestOn(until.plusMonths(1));
        if (interest.signum() <= 0) {
            accounts.saveAndFlush(account);
            return Outcome.NOTHING_EARNED;
        }
        account.deposit(interest);
        Account saved = accounts.saveAndFlush(account);
        ledger.save(new AccountTransaction(saved, TransactionType.INTEREST, interest));
        String number = saved.getAccountNumber();
        events.record("INTEREST_PAID", saved.getId(), Map.of("amount", interest, "balanceAfter", saved.getBalance(),
                "customerId", saved.getCustomerId(), "accountEnding", number.substring(number.length() - 4),
                "from", from.toString(), "until", until.minusDays(1).toString()));
        return Outcome.PAID;
    }

    /** Interest on each day's closing balance from one day up to, not including, another. */
    BigDecimal earned(Long accountId, LocalDate from, LocalDate until) {
        ZoneId zone = clock.getZone();
        Instant start = from.atStartOfDay(zone).toInstant();
        BigDecimal balance = ledger.findFirstByAccountIdAndCreatedAtLessThanOrderByIdDesc(accountId, start)
                .map(AccountTransaction::getBalanceAfter).orElse(BigDecimal.ZERO);
        Iterator<AccountTransaction> entries = ledger.findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(
                accountId, start, until.atStartOfDay(zone).toInstant()).iterator();
        AccountTransaction next = entries.hasNext() ? entries.next() : null;
        BigDecimal balanceDays = BigDecimal.ZERO;
        for (LocalDate day = from; day.isBefore(until); day = day.plusDays(1)) {
            Instant endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant();
            while (next != null && next.getCreatedAt().isBefore(endOfDay)) {
                balance = next.getBalanceAfter();
                next = entries.hasNext() ? entries.next() : null;
            }
            balanceDays = balanceDays.add(balance);
        }
        return balanceDays.multiply(annualRate).divide(HUNDRED.multiply(DAYS_IN_YEAR), 2, RoundingMode.DOWN);
    }
}
