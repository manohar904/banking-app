package com.bank.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Turns on @PreAuthorize and token checking in the services behind the gateway. */
@Configuration
@EnableMethodSecurity
public class InternalSecurityConfig {

    @Bean
    JwtDecoder internalTokenDecoder(InternalTokens tokens) {
        return tokens.decoder();
    }
}
