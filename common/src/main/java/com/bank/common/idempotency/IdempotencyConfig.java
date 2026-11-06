package com.bank.common.idempotency;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A configuration class rather than a component, so that web-layer tests (@WebMvcTest), which have
 * no database, leave the filter out.
 */
@Configuration
public class IdempotencyConfig {

    @Bean
    IdempotencyFilter idempotencyFilter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        return new IdempotencyFilter(jdbc, transactionManager);
    }
}
