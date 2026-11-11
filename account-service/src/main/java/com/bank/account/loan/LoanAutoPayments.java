package com.bank.account.loan;

import com.bank.account.loan.LoanService.AutoPayment;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Takes each loan's monthly payment from its account once it is due. Runs every hour, so a payment
 * that couldn't be taken for want of money is tried again soon after money arrives. Each loan is
 * handled in its own transaction under its own lock, so one failure doesn't stop the others, and two
 * copies of the service running at once can't take a payment twice.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.loans.auto-pay.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class LoanAutoPayments {

    private final LoanService loans;

    @Scheduled(cron = "${bank.loans.auto-pay.cron}", zone = "${bank.transfers.time-zone}")
    public Map<AutoPayment, Integer> run() {
        Map<AutoPayment, Integer> outcome = new EnumMap<>(AutoPayment.class);
        for (Long id : loans.dueLoanIds()) {
            try {
                outcome.merge(loans.takeDuePayment(id), 1, Integer::sum);
            } catch (RuntimeException e) {
                // For example the account changed at the same moment. The next run tries again.
                log.warn("Could not take the payment for loan {}: {}", id, e.toString());
            }
        }
        if (!outcome.isEmpty()) {
            log.info("Automatic loan payments: {}", outcome);
        }
        return outcome;
    }
}
