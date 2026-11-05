package com.bank.common.security;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The filter chain every service behind the gateway uses. Each service adds its own /api rules.
 * Anything not listed is refused, and /internal is only for other services.
 */
public final class ServiceSecurity {

    public static final String[] PEOPLE = {"STAFF", "CUSTOMER"};

    private ServiceSecurity() {
    }

    public static SecurityFilterChain build(HttpSecurity http, JwtDecoder decoder,
            Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> apiRules)
            throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, exception) ->
                Problems.write(response, HttpStatus.UNAUTHORIZED, "Please sign in.");
        AccessDeniedHandler denied = (request, response, exception) ->
                Problems.write(response, HttpStatus.FORBIDDEN, "You do not have access to this.");
        JwtAuthenticationConverter roles = new JwtAuthenticationConverter();
        roles.setJwtGrantedAuthoritiesConverter(jwt -> List.of(
                new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString(InternalTokens.ROLE_CLAIM))));

        http
                .authorizeHttpRequests(requests -> {
                    // Metrics are open to the private network. Only the gateway has a published port.
                    requests.requestMatchers("/actuator/health", "/actuator/prometheus", "/error").permitAll()
                            .requestMatchers("/internal/**").hasRole(InternalTokens.SERVICE_ROLE);
                    apiRules.customize(requests);
                    requests.anyRequest().denyAll();
                })
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(roles))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                // Sessions and CSRF protection live in the gateway. Here every call carries a token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
