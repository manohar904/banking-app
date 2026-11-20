package com.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The gateway behind Cloudflare's tunnel on its real domain name. The tunnel says which name and
 * scheme the browser used in X-Forwarded-Host and X-Forwarded-Proto, as it does in compose.yaml.
 * Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bank.kafka.enabled=false",
                "management.server.port=0",
                "spring.data.redis.password=",
                "bank.public-url=https://bank.example.com",
                "server.forward-headers-strategy=native"
        })
@Testcontainers
class PublicDomainTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Test
    void theRealDomainOverHttpsTellsBrowsersToAlwaysUseHttps() throws Exception {
        HttpResponse<String> page = get(Map.of("X-Forwarded-Proto", "https", "X-Forwarded-Host", "bank.example.com"));

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Strict-Transport-Security")).hasValue("max-age=31536000");
    }

    @Test
    void localhostNeverGetsHstsSoGrafanaAndKibanaStayReachable() throws Exception {
        HttpResponse<String> page = get(Map.of("X-Forwarded-Proto", "https"));

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    void plainHttpNeverGetsHsts() throws Exception {
        HttpResponse<String> page = get(Map.of("X-Forwarded-Host", "bank.example.com"));

        assertThat(page.headers().firstValue("Strict-Transport-Security")).isEmpty();
    }

    @Test
    void onlyARealHttpsAddressCountsAsAPublicOne() {
        assertThat(GatewaySecurityConfig.publicHost("https://bank.example.com")).isEqualTo("bank.example.com");
        assertThat(GatewaySecurityConfig.publicHost(" https://Bank.Example.com/ ")).isEqualTo("Bank.Example.com");
        assertThat(GatewaySecurityConfig.publicHost("")).isNull();
        assertThat(GatewaySecurityConfig.publicHost(null)).isNull();
        assertThat(GatewaySecurityConfig.publicHost("https://localhost:8443")).isNull();
        assertThat(GatewaySecurityConfig.publicHost("https://127.0.0.1:8443")).isNull();
        assertThat(GatewaySecurityConfig.publicHost("http://bank.example.com")).isNull();
        assertThatThrownBy(() -> GatewaySecurityConfig.publicHost("https://bad host"))
                .isInstanceOf(IllegalStateException.class);
    }

    private HttpResponse<String> get(Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/index.html"));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
