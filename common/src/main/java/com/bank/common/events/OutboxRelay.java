package com.bank.common.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends recorded events to Kafka, oldest first, and removes each one once Kafka has confirmed it.
 * If Kafka is down the events simply wait. A crash between sending and removing sends an event
 * twice, so delivery is at least once.
 */
@Component
@ConditionalOnProperty(name = "bank.kafka.enabled", matchIfMissing = true)
@Slf4j
public class OutboxRelay {

    public static final String TOPIC = "bank.events";
    private static final int BATCH_SIZE = 100;
    private static final long CONFIRM_TIMEOUT_SECONDS = 15;

    private record Pending(long id, String key, String payload) { }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka,
            PlatformTransactionManager transactionManager, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transactions = new TransactionTemplate(transactionManager);
        Gauge.builder("bank.outbox.pending", this, OutboxRelay::pendingCount)
                .description("Events waiting to be sent to Kafka")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${bank.events.relay-delay:1000}", initialDelayString = "${bank.events.relay-delay:1000}")
    public void publishPending() {
        transactions.executeWithoutResult(status -> {
            // Skip locked: a second instance of the service takes other rows instead of waiting.
            List<Pending> batch = jdbc.query(
                    "select id, aggregate_id, payload from outbox_events order by id limit ? for update skip locked",
                    (row, number) -> new Pending(row.getLong(1), row.getString(2), row.getString(3)), BATCH_SIZE);
            List<Object[]> sent = new ArrayList<>();
            for (Pending event : batch) {
                if (!send(event)) {
                    break; // Stop here, so that later events cannot overtake this one.
                }
                sent.add(new Object[] {event.id()});
            }
            jdbc.batchUpdate("delete from outbox_events where id = ?", sent);
        });
    }

    private boolean send(Pending event) {
        try {
            kafka.send(TOPIC, event.key(), event.payload()).get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not send an event to Kafka, will try again: {}", e.toString());
            return false;
        }
    }

    private double pendingCount() {
        Long pending = jdbc.queryForObject("select count(*) from outbox_events", Long.class);
        return pending == null ? Double.NaN : pending;
    }
}
