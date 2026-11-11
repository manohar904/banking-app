package com.bank.account;

import com.bank.common.security.ServiceSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Writes are staff-only, except that customers may send transfers. @PreAuthorize on the controller
 * adds the per-customer ownership checks.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        return ServiceSecurity.build(http, decoder, requests -> requests
                .requestMatchers(HttpMethod.GET, "/api/accounts", "/api/accounts/**").hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers(HttpMethod.POST, "/api/accounts/*/transfer").hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers("/api/accounts", "/api/accounts/**").hasRole("STAFF")
                // Loans: customers ask and may cancel their own request; staff decide; both read and
                // repay. LoanService checks that a customer only touches their own loans.
                .requestMatchers(HttpMethod.GET, "/api/loans", "/api/loans/**").hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers(HttpMethod.POST, "/api/loans", "/api/loans/*/cancel").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/loans/*/approve", "/api/loans/*/reject").hasRole("STAFF")
                .requestMatchers(HttpMethod.POST, "/api/loans/*/repay", "/api/loans/*/pay-off")
                .hasAnyRole(ServiceSecurity.PEOPLE)
                // Cards: staff issue them, record purchases, change limits and close them; both read,
                // pay and freeze. CardService checks that a customer only touches their own cards.
                .requestMatchers(HttpMethod.GET, "/api/cards", "/api/cards/**").hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers(HttpMethod.POST, "/api/cards/*/payments", "/api/cards/*/freeze",
                        "/api/cards/*/unfreeze")
                .hasAnyRole(ServiceSecurity.PEOPLE)
                .requestMatchers("/api/cards", "/api/cards/**").hasRole("STAFF"));
    }
}
