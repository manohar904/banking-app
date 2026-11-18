package com.bank.account.savings;

import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pays savings interest that is due. Runs every hour, so interest missed while the service was down is
 * paid once it is back. Each account is handled in its own transaction; its version check stops two
 * copies of the service paying the same month twice.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.savings.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SavingsInterestRunner {

    private final SavingsInterest interest;

    @Scheduled(cron = "${bank.savings.cron}", zone = "${bank.transfers.time-zone}")
    public Map<SavingsInterest.Outcome, Integer> run() {
        Map<SavingsInterest.Outcome, Integer> outcome = new EnumMap<>(SavingsInterest.Outcome.class);
        for (Long id : interest.dueIds()) {
            try {
                outcome.merge(interest.payIfDue(id), 1, Integer::sum);
            } catch (RuntimeException e) {
                // For example money moved on the account at the same moment. The next run tries again.
                log.warn("Could not pay interest on account {}: {}", id, e.toString());
            }
        }
        if (!outcome.isEmpty()) {
            log.info("Savings interest: {}", outcome);
        }
        return outcome;
    }
}
