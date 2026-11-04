package com.bank.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * Session-based sign-in for the browser front end.
 *
 * <p>The URL rules give every endpoint a safe default: writes are staff-only, and anything not
 * listed is refused. @PreAuthorize on the controllers adds the per-customer ownership checks.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico", "/error")
                        .permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/customers").hasRole("STAFF")
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers("/api/**").hasRole("STAFF")
                        .anyRequest().denyAll())
                // The front end reads the token from a cookie and sends it back in a header.
                .csrf(CsrfConfigurer::spa)
                .formLogin(login -> login
                        // loginPage also turns off Spring's generated sign-in page.
                        .loginPage("/")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .successHandler((request, response, authentication) ->
                                response.setStatus(HttpStatus.NO_CONTENT.value()))
                        // The same answer for an unknown email and a wrong password.
                        .failureHandler((request, response, exception) -> writeProblem(response,
                                HttpStatus.UNAUTHORIZED, "Incorrect email or password.")))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("JSESSIONID"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeProblem(response,
                                HttpStatus.UNAUTHORIZED, "Please sign in."))
                        .accessDeniedHandler((request, response, exception) -> writeProblem(response,
                                HttpStatus.FORBIDDEN, "You do not have access to this.")))
                // An API call that needs sign-in should answer 401, not be replayed after sign-in.
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        return http.build();
    }

    /** Stores hashes with an algorithm prefix, so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** Only fixed text goes into this JSON, so nothing needs escaping. */
    private static void writeProblem(HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}"
                .formatted(status.getReasonPhrase(), status.value(), detail));
    }
}
