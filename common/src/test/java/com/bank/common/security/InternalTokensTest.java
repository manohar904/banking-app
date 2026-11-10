package com.bank.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonWriter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * A service checking tokens from a gateway and another service, each publishing its keys over HTTP
 * the way they do in the real system.
 */
class InternalTokensTest {

    private HttpServer keyServer;
    private final AtomicReference<InternalTokens> gateway = new AtomicReference<>();
    private final AtomicReference<InternalTokens> otherService = new AtomicReference<>();
    private final AtomicInteger gatewayKeyFetches = new AtomicInteger();
    private InternalTokens service;

    @BeforeEach
    void start() throws IOException {
        gateway.set(new InternalTokens("gateway", Map.of()));
        otherService.set(new InternalTokens("other-service", Map.of()));
        keyServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        keyServer.createContext("/gateway" + InternalTokens.PUBLIC_KEYS_PATH, exchange -> {
            gatewayKeyFetches.incrementAndGet();
            respond(exchange, gateway.get());
        });
        keyServer.createContext("/other" + InternalTokens.PUBLIC_KEYS_PATH, exchange -> respond(exchange, otherService.get()));
        keyServer.start();
        String base = "http://127.0.0.1:" + keyServer.getAddress().getPort();
        service = new InternalTokens("test-service", Map.of("gateway", base + "/gateway" + InternalTokens.PUBLIC_KEYS_PATH,
                "other-service", base + "/other" + InternalTokens.PUBLIC_KEYS_PATH));
    }

    @AfterEach
    void stop() {
        keyServer.stop(0);
    }

    @Test
    void userTokenFromTheGatewayCarriesTheIdsAccessChecksNeed() {
        Jwt jwt = service.decoder().decode(gateway.get().forUser(7L, "CUSTOMER", 42L));

        assertThat(jwt.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(Callers.userIdOf(jwt)).isEqualTo(7L);
        assertThat(jwt.<Number>getClaim("customerId").longValue()).isEqualTo(42L);
    }

    @Test
    void serviceTokenFromAnotherServiceIsAccepted() {
        Jwt jwt = service.decoder().decode(otherService.get().forThisService());

        assertThat(jwt.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(jwt.getSubject()).isEqualTo("other-service");
        assertThat(Callers.userIdOf(jwt)).isNull();
    }

    @Test
    void ownTokensAreCheckedWithoutFetchingAnything() {
        Jwt jwt = service.decoder().decode(service.forThisService());

        assertThat(jwt.getSubject()).isEqualTo("test-service");
        assertThat(gatewayKeyFetches.get()).isZero();
    }

    @Test
    void aServiceCannotIssueATokenForAPerson() {
        String staffToken = otherService.get().forUser(1L, "STAFF", null);
        String ownStaffToken = service.forUser(1L, "STAFF", null);

        assertThatThrownBy(() -> service.decoder().decode(staffToken)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decoder().decode(ownStaffToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejectedEvenIfItNamesTheGateway() {
        // A fresh key pair calling itself "gateway", which is what an attacker could make.
        String forged = new InternalTokens("gateway", Map.of()).forUser(1L, "STAFF", null);

        assertThatThrownBy(() -> service.decoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void aTokenFromAnUnknownIssuerIsRejected() {
        String token = new InternalTokens("stranger", Map.of()).forThisService();

        assertThatThrownBy(() -> service.decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> service.decoder().decode("not-a-token")).isInstanceOf(JwtException.class);
    }

    @Test
    void afterTheGatewayRestartsItsNewKeyIsFetched() throws InterruptedException {
        service.decoder().decode(gateway.get().forUser(7L, "STAFF", null));
        gateway.set(new InternalTokens("gateway", Map.of())); // a restart makes a new key pair
        Thread.sleep(1100); // fetches are spaced at least a second apart

        Jwt jwt = service.decoder().decode(gateway.get().forUser(8L, "STAFF", null));

        assertThat(Callers.userIdOf(jwt)).isEqualTo(8L);
        assertThat(gatewayKeyFetches.get()).isEqualTo(2);
    }

    @Test
    void keysAreFetchedOnceNotForEveryToken() {
        for (int i = 0; i < 5; i++) {
            service.decoder().decode(gateway.get().forUser(i, "STAFF", null));
        }

        assertThat(gatewayKeyFetches.get()).isEqualTo(1);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, InternalTokens tokens) throws IOException {
        byte[] body = JsonWriter.standard().writeToString(tokens.publicKeys()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
