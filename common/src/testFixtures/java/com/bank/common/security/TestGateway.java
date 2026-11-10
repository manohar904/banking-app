package com.bank.common.security;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.boot.json.JsonWriter;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Stands in for the gateway in a service's tests: it makes tokens for people the way the gateway
 * does, and publishes its public key so the service under test trusts them. Call
 * {@link #register} from a @DynamicPropertySource method.
 */
public final class TestGateway {

    private final InternalTokens tokens;
    private final HttpServer server;

    private TestGateway(InternalTokens tokens) throws IOException {
        this.tokens = tokens;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(InternalTokens.PUBLIC_KEYS_PATH, exchange -> {
            byte[] body = JsonWriter.standard().writeToString(this.tokens.publicKeys())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    public static TestGateway start() {
        try {
            return new TestGateway(new InternalTokens(InternalTokens.GATEWAY, Map.of()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the test gateway.", e);
        }
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    /** Tells the service under test to trust this gateway's tokens. */
    public void register(DynamicPropertyRegistry registry) {
        registry.add("bank.internal.issuers[gateway]", this::url);
    }

    /** A token for a signed-in person, as the gateway would send it. customerId is null for staff. */
    public String forUser(long userId, String role, Long customerId) {
        return tokens.forUser(userId, role, customerId);
    }
}
