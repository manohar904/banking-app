package com.bank.gateway;

import com.bank.common.security.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * Session-based sign-in for the browser front end.
 *
 * <p>This only decides who is calling. What they may do is decided by the service that owns the
 * data, so the rules cannot drift apart. Anything not listed is refused, which includes /internal.
 */
@Configuration
public class GatewaySecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthServiceAuthenticationProvider authService)
            throws Exception {
        http
                .authenticationProvider(authService)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico", "/error")
                        .permitAll()
                        // Only served on the private management port. On the main port these are a 404.
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/**").authenticated()
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
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof AuthenticationServiceException) {
                                // Not the user's mistake, so do not tell them the password is wrong.
                                Problems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                                        "Sign-in is temporarily unavailable. Please try again shortly.");
                            } else {
                                // The same answer for an unknown email and a wrong password.
                                Problems.write(response, HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .deleteCookies("SESSION"))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                Problems.write(response, HttpStatus.UNAUTHORIZED, "Please sign in."))
                        .accessDeniedHandler((request, response, exception) ->
                                Problems.write(response, HttpStatus.FORBIDDEN, "You do not have access to this.")))
                // An API call that needs sign-in should answer 401, not be replayed after sign-in.
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        return http.build();
    }
}
