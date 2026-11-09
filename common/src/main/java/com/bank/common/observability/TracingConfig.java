package com.bank.common.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Tracing shared by every service. Each request gets a trace that follows it from the gateway
 * through the services it calls, sent to Tempo when an OTLP endpoint is configured (compose.yaml).
 * The defaults in bank-tracing.properties can be overridden like any other setting.
 */
@Configuration
@PropertySource("classpath:bank-tracing.properties")
public class TracingConfig {

    /**
     * Only what people ask for is traced: API calls, and the calls between services they lead to.
     * Health checks, metric scrapes, static files, background jobs and Redis connection set-up run
     * every few seconds and would bury those, so they are left out.
     */
    @Bean
    ObservationPredicate onlyTraceRequestsPeopleMake() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                String path = request.getCarrier().getRequestURI();
                return path.startsWith("/api/") || path.startsWith("/internal/");
            }
            if (name.startsWith("tasks.scheduled")) {
                return false;
            }
            // Redis commands are worth seeing inside a request, but not as traces of their own.
            boolean redis = context.getClass().getName().startsWith("io.lettuce.");
            return !redis || context.getParentObservation() != null;
        };
    }
}
