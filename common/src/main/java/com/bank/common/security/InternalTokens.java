package com.bank.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Short-lived tokens that say who a call between services is made for. Browsers never see them: the
 * gateway issues one per request from the session, and a service issues its own to call another.
 *
 * <p>Each part of the bank makes its own key pair when it starts. It signs with the private half,
 * which never leaves it, and publishes the public half at {@link #PUBLIC_KEYS_PATH}. A token is
 * trusted only if it was signed by the part it names as its issuer. Tokens for people are trusted only
 * from the gateway, and a service token can only name the service that signed it. So a service that
 * is broken into can call others as itself, but cannot act as a person or as another service.
 */
@Component
@PropertySource("classpath:bank-internal.properties")
public class InternalTokens {

    public static final String PUBLIC_KEYS_PATH = "/.well-known/internal-jwks.json";
    public static final String SERVICE_ROLE = "SERVICE";
    static final String GATEWAY = "gateway";
    static final String ROLE_CLAIM = "role";
    static final String USER_ID_CLAIM = "uid";
    static final String CUSTOMER_ID_CLAIM = "customerId";

    private static final Duration LIFETIME = Duration.ofSeconds(60);

    private final String serviceName;
    private final ECKey signingKey;
    private final JwtEncoder encoder;
    private final InternalTokenDecoder decoder;

    @Autowired
    public InternalTokens(@Value("${spring.application.name}") String serviceName, Environment environment) {
        this(serviceName, Binder.get(environment)
                .bind("bank.internal.issuers", Bindable.mapOf(String.class, String.class))
                .orElse(Map.of()));
    }

    /** issuers maps each trusted name to the base URL its public keys are published under. */
    public InternalTokens(String serviceName, Map<String, String> issuers) {
        this.serviceName = serviceName;
        try {
            this.signingKey = new ECKeyGenerator(Curve.P_256)
                    .keyID(serviceName + "-" + UUID.randomUUID())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not make a signing key.", e);
        }
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
        this.decoder = new InternalTokenDecoder(serviceName, signingKey.toPublicJWK(), issuers);
    }

    /** A token for a signed-in person. customerId is null for staff. Only the gateway's are trusted. */
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

    /** The public half of the signing key, as a JSON Web Key Set. */
    public Map<String, Object> publicKeys() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }

    private JwtClaimsSet.Builder claims(String subject, String role) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(serviceName)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .claim(ROLE_CLAIM, role);
    }

    private String encode(JwtClaimsSet.Builder claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(signingKey.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
