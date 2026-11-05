package com.bank.common.events;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the background jobs that publish to Kafka. Tests that don't need Kafka turn this off. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bank.kafka.enabled", matchIfMissing = true)
public class KafkaPublishingConfig {
}
