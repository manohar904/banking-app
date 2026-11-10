package com.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The plain HTTP port the gateway opens next to its HTTPS one, only to send browsers on. Here the
 * main port is plain HTTP too, since the test has no certificate; only the extra port is checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bank.kafka.enabled=false",
                "management.server.port=0",
                "spring.data.redis.password=",
                "bank.http-redirect.browser-https-port=9443"
        })
@Testcontainers
class HttpRedirectTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    private static final int HTTP_PORT = freePort();

    @DynamicPropertySource
    static void redirectPort(DynamicPropertyRegistry registry) {
        registry.add("bank.http-redirect.port", () -> HTTP_PORT);
        registry.add("bank.http-redirect.browser-http-port", () -> HTTP_PORT);
    }

    @LocalServerPort
    private int mainPort;

    private final HttpClient client = HttpClient.newHttpClient(); // does not follow redirects

    @Test
    void thePlainHttpPortSendsBrowsersToHttps() throws Exception {
        HttpResponse<String> page = get(HTTP_PORT, "/index.html?x=1");
        HttpResponse<String> api = get(HTTP_PORT, "/api/customers");

        assertThat(page.statusCode()).isEqualTo(302);
        assertThat(page.headers().firstValue("Location")).hasValue("https://localhost:9443/index.html?x=1");
        assertThat(api.statusCode()).isEqualTo(302);
        assertThat(api.body()).doesNotContain("customers");
    }

    @Test
    void noHstsHeaderIsSentBecauseItWouldCoverEveryPortOnLocalhost() throws Exception {
        HttpResponse<String> page = get(HTTP_PORT, "/index.html");

        assertThat(page.headers().firstValue("Strict-Transport-Security")).isEmpty();
        assertThat(mainPort).isNotEqualTo(HTTP_PORT);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
