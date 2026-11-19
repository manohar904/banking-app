package com.bank.account.savings;

import com.bank.account.Account;
import com.bank.account.AccountRepository;
import com.bank.account.AccountType;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.events.DomainEvents;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A savings account's goal: a name, an amount and, if wanted, a date to reach it by. Progress is the
 * account's balance against the amount, so it follows deposits, transfers and interest as they happen.
 * Who may see or change the account is checked by the caller.
 */
@Service
@Transactional
public class SavingsGoals {

    /** What setting a goal takes. No date means no deadline. */
    public record SetGoal(
            @NotBlank @Size(max = 40) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal targetAmount,
            LocalDate targetDate) {
    }

    /**
     * A goal and how far along it is. percent is whole and at most 100. perMonth is what to save each
     * month, this one included, to reach it by the date; null when there is no date, it is reached, or
     * the date has passed.
     */
    public record GoalResponse(Long accountId, String name, BigDecimal targetAmount, LocalDate targetDate,
            BigDecimal balance, BigDecimal remaining, int percent, boolean reached, boolean pastTargetDate,
            BigDecimal perMonth) {
    }

    private final SavingsGoalRepository goals;
    private final AccountRepository accounts;
    private final DomainEvents events;
    private final Clock clock;

    SavingsGoals(SavingsGoalRepository goals, AccountRepository accounts, DomainEvents events,
            @Value("${bank.transfers.time-zone}") ZoneId timeZone) {
        this.goals = goals;
        this.accounts = accounts;
        this.events = events;
        this.clock = Clock.system(timeZone);
    }

    @Transactional(readOnly = true)
    public Optional<GoalResponse> find(Long accountId) {
        Account account = account(accountId);
        return goals.findById(accountId).map(goal -> response(goal, account));
    }

    /** Sets the account's goal, or changes the one it has. */
    public GoalResponse set(Long accountId, SetGoal request) {
        Account account = account(accountId);
        if (account.getType() != AccountType.SAVINGS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goals are for savings accounts.");
        }
        if (request.targetDate() != null && request.targetDate().isBefore(LocalDate.now(clock))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The date to reach the goal by has already passed.");
        }
        SavingsGoal goal = goals.findById(accountId).orElseGet(() -> new SavingsGoal(accountId, clock.instant()));
        goal.set(request.name().strip(), request.targetAmount().setScale(2), request.targetDate(), clock.instant());
        goals.save(goal);
        // The name stays out of events, like other free text.
        events.record("SAVINGS_GOAL_SET", accountId, Map.of("customerId", account.getCustomerId(),
                "targetAmount", goal.getTargetAmount()));
        return response(goal, account);
    }

    /** Removes the account's goal. Removing one it does not have is fine. */
    public void remove(Long accountId) {
        Account account = account(accountId);
        goals.findById(accountId).ifPresent(goal -> {
            goals.delete(goal);
            events.record("SAVINGS_GOAL_REMOVED", accountId, Map.of("customerId", account.getCustomerId()));
        });
    }

    private Account account(Long accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found."));
    }

    GoalResponse response(SavingsGoal goal, Account account) {
        return progress(goal.getAccountId(), goal.getName(), goal.getTargetAmount(), goal.getTargetDate(),
                account.getBalance(), LocalDate.now(clock));
    }

    /** The sums, apart so they can be tested on their own. */
    static GoalResponse progress(Long accountId, String name, BigDecimal target, LocalDate targetDate,
            BigDecimal balance, LocalDate today) {
        BigDecimal remaining = target.subtract(balance).max(BigDecimal.ZERO).setScale(2);
        boolean reached = remaining.signum() == 0;
        // Rounded down, so 99.99% of the way shows 99 and only a reached goal shows 100.
        int percent = reached ? 100
                : balance.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.DOWN).intValue();
        boolean past = targetDate != null && targetDate.isBefore(today);
        BigDecimal perMonth = null;
        if (targetDate != null && !reached && !past) {
            long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(targetDate)) + 1;
            // Rounded up, so saving this much each month is always enough.
            perMonth = remaining.divide(BigDecimal.valueOf(months), 2, RoundingMode.UP);
        }
        return new GoalResponse(accountId, name, target, targetDate, balance, remaining, percent, reached, past,
                perMonth);
    }
}
