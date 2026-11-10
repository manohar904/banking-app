package com.bank.common.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Checks a token with the public key of the part of the bank it names as its issuer. Its own key is
 * known. The others are fetched from that part when first needed, and fetched again when a token
 * names a key not seen before, which is what happens after that part restarts.
 */
class InternalTokenDecoder implements JwtDecoder {

    private static final int FETCH_TIMEOUT_MILLIS = 2000;
    private static final int MAX_KEY_SET_BYTES = 50 * 1024;
    // Fetch again at most once a second, so made-up key ids cannot flood the issuer with requests.
    private static final long MIN_MILLIS_BETWEEN_FETCHES = 1000;

    private final String serviceName;
    private final JWK ownPublicKey;
    private final Map<String, String> issuers;
    private final SSLSocketFactory socketFactory;
    private final Map<String, JwtDecoder> decoders = new ConcurrentHashMap<>();

    /** issuers maps names to the URLs of their public keys. A null socketFactory means Java's usual one. */
    InternalTokenDecoder(String serviceName, JWK ownPublicKey, Map<String, String> issuers,
            SSLSocketFactory socketFactory) {
        this.serviceName = serviceName;
        this.ownPublicKey = ownPublicKey;
        this.issuers = Map.copyOf(issuers);
        this.socketFactory = socketFactory;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        String issuer;
        try {
            issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("The token could not be read.", e);
        }
        if (issuer == null || (!issuer.equals(serviceName) && !issuers.containsKey(issuer))) {
            throw new BadJwtException("The token was not issued by a trusted part of the bank.");
        }
        return decoders.computeIfAbsent(issuer, this::decoderFor).decode(token);
    }

    private JwtDecoder decoderFor(String issuer) {
        JWKSource<SecurityContext> keys = issuer.equals(serviceName)
                ? new ImmutableJWKSet<>(new JWKSet(ownPublicKey))
                : remoteKeys(issuers.get(issuer));
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256, keys));
        // Expiry and the other claims are checked by the validators below.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), InternalTokenDecoder::onlyWhatTheIssuerMayClaim));
        return decoder;
    }

    /** People only through the gateway, and a service only as itself. */
    private static OAuth2TokenValidatorResult onlyWhatTheIssuerMayClaim(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        boolean allowed = InternalTokens.SERVICE_ROLE.equals(Callers.roleOf(jwt))
                ? issuer.equals(jwt.getSubject())
                : InternalTokens.GATEWAY.equals(issuer);
        return allowed
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", issuer + " may not issue this token.", null));
    }

    private JWKSource<SecurityContext> remoteKeys(String url) {
        try {
            return JWKSourceBuilder.<SecurityContext>create(URI.create(url).toURL(),
                            new DefaultResourceRetriever(FETCH_TIMEOUT_MILLIS, FETCH_TIMEOUT_MILLIS, MAX_KEY_SET_BYTES,
                                    true, socketFactory))
                    .rateLimited(MIN_MILLIS_BETWEEN_FETCHES)
                    .retrying(true)
                    .build();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalStateException("Not a valid URL for internal keys: " + url, e);
        }
    }
}
