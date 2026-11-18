package com.bank.account.scheduled;

import com.bank.account.InsufficientFundsException;
import com.bank.account.TransferLimitExceededException;
import com.bank.common.ResourceNotFoundException;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Sends the scheduled payments that are due. Runs every hour, so a payment that couldn't be sent for
 * want of money goes soon after money arrives. Each payment is sent in its own transaction under its
 * own lock, so one failure doesn't stop the others, and two copies of the service can't send one twice.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.scheduled-payments.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class ScheduledPaymentRunner {

    private final ScheduledPaymentService payments;

    @Scheduled(cron = "${bank.scheduled-payments.cron}", zone = "${bank.transfers.time-zone}")
    public Map<String, Integer> run() {
        Map<String, Integer> outcome = new TreeMap<>();
        for (Long id : payments.dueIds()) {
            String result;
            try {
                result = payments.sendIfDue(id) ? "SENT" : "NOT_DUE";
            } catch (InsufficientFundsException e) {
                result = problem(id, "There wasn't enough money in the account.");
            } catch (TransferLimitExceededException e) {
                result = problem(id, "It would have gone over the daily transfer limit.");
            } catch (ResourceNotFoundException e) {
                result = problem(id, "The account to pay no longer exists.");
            } catch (RuntimeException e) {
                // For example the account changed at the same moment. The next run tries again.
                log.warn("Could not send scheduled payment {}: {}", id, e.toString());
                result = "ERROR";
            }
            outcome.merge(result, 1, Integer::sum);
        }
        if (!outcome.isEmpty()) {
            log.info("Scheduled payments: {}", outcome);
        }
        return outcome;
    }

    private String problem(Long id, String why) {
        try {
            payments.recordProblem(id, why);
        } catch (RuntimeException e) {
            log.warn("Could not record the problem with scheduled payment {}: {}", id, e.toString());
        }
        return "NOT_SENT";
    }
}
