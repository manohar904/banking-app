package com.bank.common.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Short-lived tokens that say who a call between services is made for. Browsers never see them: the
 * gateway issues one per request from the session, and a service issues its own to call another.
 */
@Component
public class InternalTokens {

    static final String ROLE_CLAIM = "role";
    static final String USER_ID_CLAIM = "uid";
    static final String CUSTOMER_ID_CLAIM = "customerId";
    public static final String SERVICE_ROLE = "SERVICE";

    private static final String ISSUER = "bank-internal";
    private static final Duration LIFETIME = Duration.ofSeconds(60);
    private static final int MIN_SECRET_BYTES = 32; // HS256 needs a key at least as long as its hash

    private final JwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final String serviceName;

    public InternalTokens(@Value("${bank.internal.token-secret}") String secret,
            @Value("${spring.application.name}") String serviceName) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "INTERNAL_TOKEN_SECRET must have at least " + MIN_SECRET_BYTES + " bytes.");
        }
        SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        this.serviceName = serviceName;
    }

    /** A token for a signed-in person. customerId is null for staff. */
    public String forUser(long userId, String role, Long customerId) {
        JwtClaimsSet.Builder claims = claims(String.valueOf(userId), role).claim(USER_ID_CLAIM, userId);
        if (customerId != null) {
            claims.claim(CUSTOMER_ID_CLAIM, customerId);
        }
        return encode(claims);
    }

    /** A token for this service itself, which is what the /internal endpoints require. */
    public String forThisService() {
        return encode(claims(serviceName, SERVICE_ROLE));
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    private static JwtClaimsSet.Builder claims(String subject, String role) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .claim(ROLE_CLAIM, role);
    }

    private String encode(JwtClaimsSet.Builder claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
