package com.bank.gateway;

import com.bank.common.security.InternalTokens;
import com.bank.common.security.Problems;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
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
@Slf4j
@Configuration
public class GatewaySecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthServiceAuthenticationProvider authService,
            @Value("${" + HttpRedirect.PORT + ":}") String redirectPort,
            @Value("${bank.http-redirect.browser-http-port:0}") int browserHttpPort,
            @Value("${bank.http-redirect.browser-https-port:0}") int browserHttpsPort,
            @Value("${bank.public-url:}") String publicUrl) throws Exception {
        String publicHost = publicHost(publicUrl);
        http
                // Not authenticationProvider(): Spring would also add this bean to a parent manager,
                // which is asked again after a failure, so each failed sign-in would be checked twice.
                .authenticationManager(new ProviderManager(authService))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/favicon.ico", "/error")
                        .permitAll()
                        // The services fetch this to check the gateway's tokens. Public keys are safe to show.
                        .requestMatchers(HttpMethod.GET, InternalTokens.PUBLIC_KEYS_PATH).permitAll()
                        // Only served on the private management port. On the main port these are a 404.
                        .requestMatchers("/actuator/health", "/actuator/prometheus", "/actuator/jwks").permitAll()
                        // For people who cannot sign in. Every other API call needs a session.
                        .requestMatchers(HttpMethod.POST, "/api/auth/password-reset", "/api/auth/password-reset/confirm")
                        .permitAll()
                        // The second step of two-step sign-in, before the session is signed in. It only
                        // works for a session whose first step, the password, was right.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login/code").permitAll()
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
                            if (exception instanceof SignInCodeRequiredException codeSent) {
                                SignInCodeController.codeSent(request, response, codeSent);
                            } else if (exception instanceof LockedException) {
                                // Also answered for emails with no account, so it reveals nothing.
                                Problems.write(response, HttpStatus.TOO_MANY_REQUESTS,
                                        "Too many sign-in attempts. Please wait up to 15 minutes and try again.");
                            } else if (exception instanceof AuthenticationServiceException) {
                                // Not the user's mistake, so do not tell them the password is wrong. The
                                // cause is logged, without the email or password, so it can be looked into.
                                log.warn("Sign-in could not be completed: {}", exception.getMessage(),
                                        exception.getCause() != null ? exception.getCause() : exception);
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
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"))
                        // HSTS only on the real domain name, over HTTPS. For "localhost" a browser would
                        // apply it to every port, including Kibana and Grafana, which are plain HTTP. Not
                        // for subdomains either: other sites on the same domain are not the bank's to decide.
                        .httpStrictTransportSecurity(hsts -> {
                            if (publicHost == null) {
                                hsts.disable();
                            } else {
                                hsts.requestMatcher(request -> request.isSecure()
                                                && publicHost.equalsIgnoreCase(request.getServerName()))
                                        .includeSubDomains(false)
                                        .maxAgeInSeconds(Duration.ofDays(365).toSeconds());
                            }
                        }));
        if (!redirectPort.isBlank()) {
            // Anything arriving on the plain HTTP port (see HttpRedirect) is sent on to HTTPS. The
            // ports are the ones the browser sees, which compose.yaml may publish differently.
            http.redirectToHttps(Customizer.withDefaults())
                    .portMapper(ports -> ports.http(browserHttpPort).mapsTo(browserHttpsPort));
        }
        return http.build();
    }

    /** The host name of the bank's public HTTPS address, or null when it has none or it is this computer. */
    static String publicHost(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(publicUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("bank.public-url is not a valid address: " + publicUrl, e);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.equalsIgnoreCase("localhost")
                || host.startsWith("127.") || host.equals("[::1]")) {
            return null;
        }
        return host;
    }
}
