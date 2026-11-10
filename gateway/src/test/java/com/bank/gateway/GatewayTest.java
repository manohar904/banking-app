package com.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.common.security.Callers;
import com.bank.common.security.InternalTokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the real gateway on a real port, with small stand-ins for the services behind it and a real
 * Redis for the sessions. Requires Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "bank.kafka.enabled=false",
                "management.server.port=0",
                "spring.data.redis.password="
        })
@Testcontainers
class GatewayTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8.8.4-alpine").withExposedPorts(6379);

    private static final StubService customers = new StubService("customer-service");
    private static final StubService accounts = new StubService("account-service");
    private static final StubService auth = new StubService("auth-service");

    @DynamicPropertySource
    static void serviceUrls(DynamicPropertyRegistry registry) {
        registry.add("bank.services.customer-url", customers::url);
        registry.add("bank.services.account-url", accounts::url);
        registry.add("bank.services.auth-url", auth::url);
    }

    @AfterAll
    static void stopStubs() {
        customers.stop();
        accounts.stop();
        auth.stop();
    }

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private InternalTokens tokens;

    private Browser browser;

    @BeforeEach
    void freshBrowser() {
        browser = new Browser(port);
        customers.reset();
        accounts.reset();
        auth.reset();
    }

    @Test
    void frontEndIsServedWithoutSigningIn() throws Exception {
        HttpResponse<String> page = browser.get("/index.html");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(page.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(browser.get("/").statusCode()).isEqualTo(200);
        assertThat(browser.get("/app.js").statusCode()).isEqualTo(200);
        assertThat(browser.get("/styles.css").statusCode()).isEqualTo(200);
    }

    @Test
    void anonymousCallsStopAtTheGateway() throws Exception {
        assertThat(browser.get("/api/customers").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/accounts/1").statusCode()).isEqualTo(401);

        assertThat(customers.lastPath.get()).isNull();
        assertThat(accounts.lastPath.get()).isNull();
    }

    @Test
    void anythingOutsideTheKnownFilesAndApiIsRefused() throws Exception {
        assertThat(browser.get("/login").statusCode()).isEqualTo(401);
        assertThat(browser.get("/application.yaml").statusCode()).isEqualTo(401);
        assertThat(browser.get("/internal/customers/1").statusCode()).isEqualTo(401);

        browser.signIn();
        assertThat(browser.get("/internal/customers/1").statusCode()).isEqualTo(403);
        assertThat(customers.lastPath.get()).isNull();
    }

    @Test
    void forgotPasswordWorksWithoutSigningInAndGoesToTheAuthServiceAsThisService() throws Exception {
        auth.answer(202, "");

        HttpResponse<String> response = browser.write("POST", "/api/auth/password-reset", "{\"email\": \"ada@example.com\"}");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(auth.lastPath.get()).isEqualTo("/internal/password-reset-requests");
        assertThat(auth.lastBody.get()).contains("ada@example.com");
        Jwt token = tokens.decoder().decode(auth.lastAuthorization.get().substring("Bearer ".length()));
        assertThat(token.getClaimAsString("role")).isEqualTo("SERVICE");
    }

    @Test
    void theGatewayPublishesOnlyThePublicHalfOfItsSigningKey() throws Exception {
        HttpResponse<String> response = browser.get(InternalTokens.PUBLIC_KEYS_PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"kty\":\"EC\"", "\"x\":", "\"y\":").doesNotContain("\"d\":");
    }

    @Test
    void theManagementPortServesTheSamePublicKeyForTheServices() throws Exception {
        HttpResponse<String> fromManagement = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/jwks")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String fromMainPort = browser.get(InternalTokens.PUBLIC_KEYS_PATH).body();

        assertThat(fromManagement.statusCode()).isEqualTo(200);
        assertThat(fromManagement.body()).contains("\"kty\":\"EC\"").doesNotContain("\"d\":");
        assertThat(kid(fromManagement.body())).isEqualTo(kid(fromMainPort));
    }

    private static String kid(String keySet) {
        return keySet.replaceAll("(?s).*\"kid\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void aRefusedResetLinkKeepsItsMessage() throws Exception {
        auth.answer(400, "{\"detail\":\"This reset link is invalid\"}");

        HttpResponse<String> response = browser.write("POST", "/api/auth/password-reset/confirm", "{\"token\": \"x\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("This reset link is invalid");
        assertThat(auth.lastPath.get()).isEqualTo("/internal/password-resets");
    }

    @Test
    void forgotPasswordStillNeedsACsrfToken() throws Exception {
        HttpResponse<String> refused = browser.send(browser.request("/api/auth/password-reset")
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}")));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(auth.lastPath.get()).isNull();
    }

    @Test
    void anOversizedForgotPasswordRequestIsRefused() throws Exception {
        HttpResponse<String> response = browser.write("POST", "/api/auth/password-reset", "x".repeat(5000));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(auth.lastPath.get()).isNull();
    }

    @Test
    void forgotPasswordSaysSoWhenTheAuthServiceIsDown() throws Exception {
        auth.stop();
        try {
            assertThat(browser.write("POST", "/api/auth/password-reset", "{}").statusCode()).isEqualTo(503);
        } finally {
            auth.start();
        }
    }

    @Test
    void onlyTheForgotPasswordPathsAreOpen() throws Exception {
        assertThat(browser.write("POST", "/api/auth/password", "{}").statusCode()).isEqualTo(401);
        assertThat(browser.write("POST", "/api/auth/password-reset/other", "{}").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/auth/password-reset").statusCode()).isEqualTo(401);
    }

    @Test
    void tooManySignInAttemptsAreReportedAsSuch() throws Exception {
        auth.signInsLimited.set(true);

        HttpResponse<String> response = browser.signIn();

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("Too many sign-in attempts");
        assertThat(auth.passwordChecks.get()).isEqualTo(1);
        assertThat(browser.get("/api/customers").statusCode()).isEqualTo(401);
    }

    @Test
    void theBrowsersAddressIsSentWithTheSignIn() throws Exception {
        browser.signIn();

        assertThat(auth.lastSignInBody.get()).contains("\"clientAddress\":\"127.0.0.1\"");
    }

    @Test
    void eachSignInAttemptChecksThePasswordOnce() throws Exception {
        browser.signIn("wrong-password");
        assertThat(auth.passwordChecks.get()).isEqualTo(1);

        browser.signIn();
        assertThat(auth.passwordChecks.get()).isEqualTo(2);
    }

    @Test
    void wrongPasswordIsTheSameAnswerAsAnyOtherFailedSignIn() throws Exception {
        HttpResponse<String> response = browser.signIn("wrong-password");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("Incorrect email or password.");
        assertThat(browser.get("/api/customers").statusCode()).isEqualTo(401);
    }

    @Test
    void callsGoToTheServiceThatOwnsTheData() throws Exception {
        browser.signIn();

        browser.get("/api/customers?page=2&size=5");
        assertThat(customers.lastPath.get()).isEqualTo("/api/customers?page=2&size=5");
        browser.get("/api/customers/5");
        assertThat(customers.lastPath.get()).isEqualTo("/api/customers/5");
        browser.get("/api/accounts");
        assertThat(accounts.lastPath.get()).isEqualTo("/api/accounts");
        browser.get("/api/accounts/9/transactions");
        assertThat(accounts.lastPath.get()).isEqualTo("/api/accounts/9/transactions");
        browser.get("/api/auth/me");
        assertThat(auth.lastPath.get()).isEqualTo("/api/auth/me");
        browser.write("PUT", "/api/customers/5/login", "{}");
        assertThat(auth.lastPath.get()).isEqualTo("/api/customers/5/login");
    }

    @Test
    void unknownApiPathsAreNotForwarded() throws Exception {
        browser.signIn();

        assertThat(browser.get("/api/other").statusCode()).isEqualTo(404);
        assertThat(browser.get("/api/customersfoo").statusCode()).isEqualTo(404);
    }

    @Test
    void servicesGetASignedTokenInsteadOfTheBrowsersCredentials() throws Exception {
        browser.signIn();

        browser.send(browser.request("/api/customers/1").header("Authorization", "Bearer made-up").GET());

        assertThat(customers.lastCookie.get()).isNull();
        Jwt token = tokens.decoder().decode(customers.lastAuthorization.get().substring("Bearer ".length()));
        assertThat(token.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(Callers.userIdOf(token)).isEqualTo(7L);
        assertThat(token.<Number>getClaim("customerId").longValue()).isEqualTo(42L);
    }

    @Test
    void writesNeedACsrfTokenAndAreNotForwardedWithoutOne() throws Exception {
        browser.signIn();

        HttpResponse<String> refused = browser.send(browser.request("/api/accounts/1/deposit")
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}")));

        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(accounts.lastPath.get()).isNull();
    }

    @Test
    void answersComeBackUnchanged() throws Exception {
        browser.signIn();
        accounts.answer(201, "{\"balance\":100.10}");

        HttpResponse<String> response = browser.write("POST", "/api/accounts", "{\"customerId\": 1}");

        assertThat(response.statusCode()).isEqualTo(201);
        // Exact text: the front end relies on amounts keeping their digits.
        assertThat(response.body()).isEqualTo("{\"balance\":100.10}");
        assertThat(response.headers().firstValue("Location")).contains("/somewhere");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(accounts.lastBody.get()).isEqualTo("{\"customerId\": 1}");
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        browser.signIn();
        assertThat(browser.get("/api/customers").statusCode()).isEqualTo(200);

        assertThat(browser.write("POST", "/api/auth/logout", "").statusCode()).isEqualTo(204);

        assertThat(browser.get("/api/customers").statusCode()).isEqualTo(401);
    }

    @Test
    void serviceThatIsDownGivesAnAnswerSayingNothingChanged() throws Exception {
        browser.signIn();
        accounts.stop();
        try {
            HttpResponse<String> response = browser.write("POST", "/api/accounts/1/deposit", "{}");

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("Nothing was changed");
        } finally {
            accounts.start();
        }
    }

    @Test
    void lostAnswerIsNotReportedAsNothingChanged() throws Exception {
        browser.signIn();
        accounts.hangUp.set(true);

        HttpResponse<String> response = browser.write("POST", "/api/accounts/1/deposit", "{}");

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("may or may not have been applied");
    }

    @Test
    void sessionsAreKeptInRedisAndRemovedOnSignOut() throws Exception {
        browser.signIn();
        String session = browser.cookie("SESSION");
        assertThat(session).isNotNull();
        assertThat(sessionKeys()).anyMatch(key -> key.endsWith(sessionId(session)));

        browser.write("POST", "/api/auth/logout", "");

        assertThat(sessionKeys()).noneMatch(key -> key.endsWith(sessionId(session)));
    }

    /** For example a session saved by an older gateway whose classes have since changed. */
    @Test
    void sessionThatCannotBeReadCountsAsSignedOut() throws Exception {
        browser.signIn();
        String key = sessionKeys().stream().filter(k -> k.endsWith(sessionId(browser.cookie("SESSION"))))
                .findFirst().orElseThrow();

        redis.execInContainer("redis-cli", "hset", key, "sessionAttr:SPRING_SECURITY_CONTEXT", "not-java");

        HttpResponse<String> response = browser.get("/api/customers");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(customers.lastPath.get()).isNull();
    }

    private static List<String> sessionKeys() throws Exception {
        String keys = redis.execInContainer("redis-cli", "--scan", "--pattern", "bank:sessions:sessions:*").getStdout();
        return keys.lines().filter(line -> !line.isBlank()).toList();
    }

    /** The cookie holds the session id in Base64. */
    private static String sessionId(String cookie) {
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    /** The service continues the gateway's trace, so one request can be followed across services. */
    @Test
    void forwardedRequestsCarryTheTrace() throws Exception {
        browser.signIn();
        String incomingTrace = "4bf92f3577b34da6a3ce929d0e0e4736";

        browser.send(browser.request("/api/accounts/1")
                .header("traceparent", "00-" + incomingTrace + "-00f067aa0ba902b7-01").GET());

        String forwarded = accounts.lastTraceparent.get();
        assertThat(forwarded).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        // Same trace, new parent: the gateway's own span for the call.
        assertThat(forwarded.split("-")[1]).isEqualTo(incomingTrace);
        assertThat(forwarded.split("-")[2]).isNotEqualTo("00f067aa0ba902b7");
    }

    @Test
    void idempotencyKeyIsPassedOnAndAReplayIsMarked() throws Exception {
        browser.signIn();

        HttpResponse<String> response = browser.send(browser.writeRequest("POST", "/api/accounts/1/deposit", "{}")
                .header("Idempotency-Key", "abc-123"));

        assertThat(accounts.lastIdempotencyKey.get()).isEqualTo("abc-123");
        assertThat(response.headers().firstValue("Idempotent-Replayed")).contains("true");
    }

    @Test
    void lostAnswerWithAKeySaysItIsSafeToSendAgain() throws Exception {
        browser.signIn();
        accounts.hangUp.set(true);

        HttpResponse<String> response = browser.send(browser.writeRequest("POST", "/api/accounts/1/deposit", "{}")
                .header("Idempotency-Key", "abc-124"));

        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(response.body()).contains("safe to send this again with the same Idempotency-Key");
    }

    @Test
    void authServiceBeingDownIsNotReportedAsAWrongPassword() throws Exception {
        auth.stop();
        try {
            HttpResponse<String> response = browser.signIn();

            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("temporarily unavailable");
        } finally {
            auth.start();
        }
    }

    /** Keeps cookies and sends the CSRF token back, as the front end does. */
    private static final class Browser {

        private final int port;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client;

        Browser(int port) {
            this.port = port;
            this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
        }

        HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        }

        HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET());
        }

        HttpResponse<String> write(String method, String path, String json) throws Exception {
            return send(writeRequest(method, path, json));
        }

        HttpRequest.Builder writeRequest(String method, String path, String json) throws Exception {
            BodyPublisher body = json.isEmpty() ? BodyPublishers.noBody() : BodyPublishers.ofString(json);
            return request(path).header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", csrfToken()).method(method, body);
        }

        HttpResponse<String> signIn() throws Exception {
            return signIn("right-password");
        }

        HttpResponse<String> signIn(String password) throws Exception {
            return send(request("/api/auth/login")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-XSRF-TOKEN", csrfToken())
                    .POST(BodyPublishers.ofString("email=ada%40example.com&password=" + password)));
        }

        private String csrfToken() throws Exception {
            if (cookie() == null) {
                get("/index.html");
            }
            return cookie();
        }

        private String cookie() {
            return cookie("XSRF-TOKEN");
        }

        String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(java.net.HttpCookie::getValue)
                    .findFirst().orElse(null);
        }
    }

    /** Stands in for a service: records what arrived and returns a canned answer. */
    private static final class StubService {

        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicReference<String> lastCookie = new AtomicReference<>();
        final AtomicReference<String> lastAuthorization = new AtomicReference<>();
        final AtomicReference<String> lastBody = new AtomicReference<>();
        final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
        final AtomicReference<String> lastTraceparent = new AtomicReference<>();
        final AtomicInteger passwordChecks = new AtomicInteger();
        final AtomicReference<String> lastSignInBody = new AtomicReference<>();
        final AtomicReference<Boolean> signInsLimited = new AtomicReference<>(false);
        final AtomicReference<Boolean> hangUp = new AtomicReference<>(false);
        private volatile int status = 200;
        private volatile String body = "{}";
        private final int port;
        private HttpServer server;

        StubService(String name) {
            try {
                this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                this.port = server.getAddress().getPort();
                serve();
            } catch (IOException e) {
                throw new IllegalStateException(name, e);
            }
        }

        String url() {
            return "http://127.0.0.1:" + port;
        }

        void reset() {
            lastPath.set(null);
            lastCookie.set(null);
            lastAuthorization.set(null);
            lastBody.set(null);
            lastIdempotencyKey.set(null);
            lastTraceparent.set(null);
            passwordChecks.set(0);
            signInsLimited.set(false);
            hangUp.set(false);
            answer(200, "{}");
        }

        void answer(int status, String body) {
            this.status = status;
            this.body = body;
        }

        void stop() {
            server.stop(0);
        }

        void start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                serve();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private void serve() {
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // No pooled connections, so that stopping a stub looks like a service that is really down.
            exchange.getResponseHeaders().set("Connection", "close");
            if (exchange.getRequestURI().getPath().equals("/internal/authentications")) {
                passwordChecks.incrementAndGet();
                lastSignInBody.set(requestBody);
                if (signInsLimited.get()) {
                    respond(exchange, 429, "{}");
                    return;
                }
                boolean right = requestBody.contains("right-password");
                respond(exchange, right ? 200 : 401, right
                        ? "{\"userId\":7,\"email\":\"ada@example.com\",\"role\":\"CUSTOMER\",\"customerId\":42}" : "{}");
                return;
            }
            lastPath.set(exchange.getRequestURI().toString());
            lastCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(requestBody);
            lastIdempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            lastTraceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            if (hangUp.get()) {
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Location", "/somewhere");
            exchange.getResponseHeaders().set("Idempotent-Replayed", "true");
            exchange.getResponseHeaders().set("Set-Cookie", "leak=1");
            respond(exchange, status, body);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
