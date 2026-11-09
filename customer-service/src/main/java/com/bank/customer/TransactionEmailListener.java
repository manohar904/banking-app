package com.bank.customer;

import com.bank.common.events.OutboxRelay;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Separate from TransactionEmails, so tests without Kafka can still use that class. */
@Component
@ConditionalOnProperty(name = "bank.kafka.enabled", matchIfMissing = true)
@RequiredArgsConstructor
class TransactionEmailListener {

    private final TransactionEmails emails;

    @KafkaListener(topics = OutboxRelay.TOPIC, groupId = "transaction-emails")
    void onEvent(String payload) {
        emails.handle(payload);
    }
}
