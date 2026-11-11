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
                .hasRole("STAFF")
                // Help: only customers ask; both sides read, reply and close. HelpService checks
                // that a customer only touches their own questions.
                .requestMatchers(HttpMethod.POST, "/api/help-requests").hasRole("CUSTOMER")
                .requestMatchers("/api/help-requests", "/api/help-requests/*", "/api/help-requests/*/messages",
                        "/api/help-requests/*/close")
                .hasAnyRole(ServiceSecurity.PEOPLE));
    }
}
