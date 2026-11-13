package com.bank.account.card;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Makes each open card's monthly statement once it is due. Runs every hour, so a statement missed while
 * the service was down is made soon after it starts again. Each card is handled in its own transaction
 * under its own lock, so one failure doesn't stop the others, and two copies of the service running at
 * once can't make a statement twice. A card several months behind catches up one month per run.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.cards.statements.enabled", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CardStatements {

    private final CardService cards;

    @Scheduled(cron = "${bank.cards.statements.cron}", zone = "${bank.transfers.time-zone}")
    public int run() {
        int made = 0;
        for (Long id : cards.cardIdsWithStatementDue()) {
            try {
                if (cards.makeDueStatement(id)) {
                    made++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not make the statement for card {}: {}", id, e.toString());
            }
        }
        if (made > 0) {
            log.info("Card statements made: {}", made);
        }
        return made;
    }
}
