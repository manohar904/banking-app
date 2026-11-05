package com.bank.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

class InternalTokensTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256";

    private final InternalTokens tokens = new InternalTokens(SECRET, "test-service");

    @Test
    void userTokenCarriesTheIdsAccessChecksNeed() {
        Jwt jwt = tokens.decoder().decode(tokens.forUser(7L, "CUSTOMER", 42L));

        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(Callers.userIdOf(jwt)).isEqualTo(7L);
        assertThat(jwt.<Number>getClaim("customerId").longValue()).isEqualTo(42L);
    }

    @Test
    void serviceTokenIsNotForAPerson() {
        Jwt jwt = tokens.decoder().decode(tokens.forThisService());

        assertThat(jwt.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(jwt.getSubject()).isEqualTo("test-service");
        assertThat(Callers.userIdOf(jwt)).isNull();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String forged = new InternalTokens("another-secret-that-is-also-long-enough", "evil").forUser(1L, "STAFF", null);

        assertThatThrownBy(() -> tokens.decoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretStopsTheServiceFromStarting() {
        assertThatThrownBy(() -> new InternalTokens("too-short", "test-service"))
                .isInstanceOf(IllegalStateException.class);
    }
}
