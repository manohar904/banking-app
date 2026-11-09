package com.bank.customer;

import com.bank.common.security.ServiceSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** Writes are staff-only. @PreAuthorize on the controller adds the per-customer ownership check. */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return ServiceSecurity.build(http, decoder, requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/customers").hasRole("STAFF")
                .requestMatchers(HttpMethod.GET, "/api/customers/*").hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers("/api/customers", "/api/customers/*", "/api/customers/*/transaction-emails")
                .hasRole("STAFF"));
    }
}
