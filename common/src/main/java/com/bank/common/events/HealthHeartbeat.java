package com.bank.common.events;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Regularly reports this service's own health: as a metric for Grafana and as a Kafka message that
 * ends up in Kibana. Heartbeats that stop arriving are themselves a sign the service is in trouble.
 */
@Component
@ConditionalOnProperty(name = "bank.kafka.enabled", matchIfMissing = true)
@Slf4j
public class HealthHeartbeat {

    public static final String TOPIC = "bank.health";

    private final HealthEndpoint health;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper json;
    private final String serviceName;
    private final AtomicInteger healthy = new AtomicInteger();

    public HealthHeartbeat(HealthEndpoint health, KafkaTemplate<String, String> kafka, JsonMapper json,
            MeterRegistry metrics, @Value("${spring.application.name}") String serviceName) {
        this.health = health;
        this.kafka = kafka;
        this.json = json;
        this.serviceName = serviceName;
        Gauge.builder("bank.health.status", healthy, AtomicInteger::get)
                .description("1 if the service reports itself healthy, otherwise 0")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${bank.health.heartbeat-interval:30000}",
            initialDelayString = "${bank.health.heartbeat-interval:30000}")
    public void report() {
        Status status = health.health().getStatus();
        healthy.set(Status.UP.equals(status) ? 1 : 0);
        String heartbeat = json.writeValueAsString(Map.of(
                "service", serviceName, "status", status.getCode(), "checkedAt", Instant.now().toString()));
        try {
            kafka.send(TOPIC, serviceName, heartbeat);
        } catch (RuntimeException e) {
            // The metric above is already up to date. A missed heartbeat is not worth more than a note.
            log.debug("Could not send a health heartbeat to Kafka: {}", e.toString());
        }
    }
}
