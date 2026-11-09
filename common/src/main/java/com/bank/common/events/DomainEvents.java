package com.bank.common.events;

import com.bank.common.security.Callers;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records that something happened, in the same database transaction as the change itself. So an
 * event exists exactly when the change committed, and Kafka being down can never fail a deposit.
 * OutboxRelay sends the events to Kafka afterwards. Each one is also written to the audit log, with
 * who it was done for, because every change that matters already passes through here.
 *
 * <p>Keep names, emails and passwords out of event data: events are copied to Kafka and Elasticsearch.
 */
@Component
public class DomainEvents {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final String serviceName;

    public DomainEvents(JdbcTemplate jdbc, JsonMapper json, @Value("${spring.application.name}") String serviceName) {
        this.jdbc = jdbc;
        this.json = json;
        this.serviceName = serviceName;
    }

    /** MANDATORY: without a surrounding transaction the event could outlive a change that failed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String type, Object aggregateId, Map<String, ?> data) {
        Long actorUserId = null;
        String actorRole = "SYSTEM";
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (caller instanceof JwtAuthenticationToken token) {
            actorUserId = Callers.userIdOf(token.getToken());
            actorRole = Callers.roleOf(token.getToken());
        }
        Map<String, Object> event = new LinkedHashMap<>();
        // Delivery can repeat after a failure. Consumers use this id to ignore a repeat.
        event.put("eventId", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("service", serviceName);
        event.put("aggregateId", String.valueOf(aggregateId));
        event.put("occurredAt", Instant.now().toString());
        event.put("actorUserId", actorUserId);
        event.put("actorRole", actorRole);
        event.put("data", data);
        jdbc.update("insert into audit_log (actor_user_id, actor_role, action, target_id, details) values (?, ?, ?, ?, ?)",
                actorUserId, actorRole, type, String.valueOf(aggregateId), json.writeValueAsString(data));
        jdbc.update("insert into outbox_events (aggregate_id, payload) values (?, ?)",
                String.valueOf(aggregateId), json.writeValueAsString(event));
    }
}
