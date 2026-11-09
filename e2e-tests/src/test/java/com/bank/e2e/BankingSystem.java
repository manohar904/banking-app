package com.bank.e2e;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts PostgreSQL and the four packaged jars, wired together the same way compose.yaml does. */
final class BankingSystem implements AutoCloseable {

    private static final Duration START_TIMEOUT = Duration.ofMinutes(3);
    private static final String TOKEN_SECRET = "an-end-to-end-test-secret-that-is-long-enough";
    private static final String REDIS_PASSWORD = "an-end-to-end-redis-password";

    private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
    private final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.2");
    private final GenericContainer<?> redis = new GenericContainer<>("redis:8.8.4-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);
    private final Map<String, Process> processes = new LinkedHashMap<>();
    private Map<String, String> urls;
    private final HttpClient http = HttpClient.newHttpClient();
    final TraceReceiver traces;
    final int gatewayPort;
    final int customerPort;
    final int accountPort;
    final int authPort;
    final int gatewayManagementPort;

    BankingSystem() throws Exception {
        gatewayPort = freePort();
        customerPort = freePort();
        accountPort = freePort();
        authPort = freePort();
        gatewayManagementPort = freePort();
        traces = new TraceReceiver();
    }

    void start(String adminEmail, String adminPassword) throws Exception {
        postgres.start();
        kafka.start();
        redis.start();
        for (String database : List.of("bank_customers", "bank_accounts", "bank_auth")) {
            postgres.execInContainer("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                    "-c", "create database " + database);
        }
        urls = Map.of(
                "CUSTOMER_SERVICE_URL", "http://127.0.0.1:" + customerPort,
                "ACCOUNT_SERVICE_URL", "http://127.0.0.1:" + accountPort,
                "AUTH_SERVICE_URL", "http://127.0.0.1:" + authPort);

        launch("customer-service", customerPort, urls, Map.of("DB_NAME", "bank_customers"));
        launch("account-service", accountPort, urls, Map.of("DB_NAME", "bank_accounts"));
        launch("auth-service", authPort, urls, Map.of(
                "DB_NAME", "bank_auth", "BANK_ADMIN_EMAIL", adminEmail, "BANK_ADMIN_PASSWORD", adminPassword));
        launchGateway();

        awaitUp("customer-service", customerPort, "/actuator/health");
        awaitUp("account-service", accountPort, "/actuator/health");
        awaitUp("auth-service", authPort, "/actuator/health");
        awaitUp("gateway", gatewayPort, "/index.html");
    }

    /** Stops the gateway process and starts a new one, as a redeploy or crash would. */
    void restartGateway() throws Exception {
        Process gateway = processes.remove("gateway");
        gateway.destroy();
        gateway.waitFor();
        launchGateway();
        awaitUp("gateway", gatewayPort, "/index.html");
    }

    private void launchGateway() throws IOException {
        launch("gateway", gatewayPort, urls, Map.of(
                "MANAGEMENT_SERVER_PORT", String.valueOf(gatewayManagementPort),
                "REDIS_HOST", redis.getHost(),
                "REDIS_PORT", String.valueOf(redis.getMappedPort(6379)),
                "REDIS_PASSWORD", REDIS_PASSWORD));
    }

    private void launch(String service, int port, Map<String, String> urls, Map<String, String> extra)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-jar", System.getProperty("jar." + service));
        Map<String, String> env = builder.environment();
        env.putAll(urls);
        env.putAll(extra);
        env.put("SERVER_PORT", String.valueOf(port));
        env.put("INTERNAL_TOKEN_SECRET", TOKEN_SECRET);
        env.put("KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        env.put("BANK_HEALTH_HEARTBEATINTERVAL", "2000");
        env.put("BANK_EVENTS_RELAYDELAY", "200");
        env.put("DB_HOST", postgres.getHost());
        env.put("DB_PORT", String.valueOf(postgres.getMappedPort(5432)));
        env.put("DB_USERNAME", postgres.getUsername());
        env.put("DB_PASSWORD", postgres.getPassword());
        env.put("MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT", traces.endpoint());
        env.put("MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_SCHEDULE_DELAY", "200ms");
        File log = logFile(service).toFile();
        Files.createDirectories(log.toPath().getParent());
        builder.redirectErrorStream(true).redirectOutput(log);
        processes.put(service, builder.start());
    }

    private void awaitUp(String service, int port, String path) throws Exception {
        Instant deadline = Instant.now().plus(START_TIMEOUT);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        while (Instant.now().isBefore(deadline)) {
            try {
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (IOException notUpYet) {
                // Keep waiting.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException(service + " did not start. Its log:\n" + Files.readString(logFile(service)));
    }

    String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    private static Path logFile(String service) {
        return Path.of("build", "e2e-logs", service + ".log");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        processes.values().forEach(Process::destroy);
        traces.close();
        redis.stop();
        kafka.stop();
        postgres.stop();
    }
}
