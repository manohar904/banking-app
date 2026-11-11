package com.bank.gateway;

import com.bank.common.idempotency.IdempotencyFilter;
import com.bank.common.security.InternalTls;
import com.bank.common.security.InternalTokens;
import com.bank.common.security.Problems;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forwards API calls to the service that owns the data, swapping the browser's session for a
 * short-lived internal token. Requests are never retried here: without an Idempotency-Key a repeated
 * withdrawal would take money twice. Callers that send one can safely send the request again.
 */
@RestController
@Slf4j
public class ApiProxyController {

    /** Allow-lists, so cookies and any Authorization header a caller made up never reach a service. */
    private static final List<String> REQUEST_HEADERS =
            List.of(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_LANGUAGE, HttpHeaders.CONTENT_TYPE,
                    IdempotencyFilter.KEY_HEADER);
    private static final List<String> RESPONSE_HEADERS =
            List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION, IdempotencyFilter.REPLAYED_HEADER);

    private static final Pattern CUSTOMER_LOGIN = Pattern.compile("/api/customers/[^/]+/login");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final InternalTokens tokens;
    private final Tracer tracer;
    private final Propagator propagator;
    private final String customerUrl;
    private final String accountUrl;
    private final String authUrl;

    ApiProxyController(InternalTokens tokens, InternalTls tls, Tracer tracer, Propagator propagator,
            @Value("${bank.services.customer-url}") String customerUrl,
            @Value("${bank.services.account-url}") String accountUrl,
            @Value("${bank.services.auth-url}") String authUrl) {
        this.httpClient = tls.configure(HttpClient.newBuilder())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.tokens = tokens;
        this.tracer = tracer;
        this.propagator = propagator;
        this.customerUrl = customerUrl;
        this.accountUrl = accountUrl;
        this.authUrl = authUrl;
    }

    @RequestMapping("/api/**")
    public void forward(@AuthenticationPrincipal SignedInUser user, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String serviceUrl = serviceFor(path);
        if (serviceUrl == null) {
            Problems.write(response, HttpStatus.NOT_FOUND, "No such resource.");
            return;
        }
        String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();

        HttpRequest.Builder forwarded = HttpRequest.newBuilder(URI.create(serviceUrl + path + query))
                .timeout(REQUEST_TIMEOUT)
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + tokens.forUser(user.userId(), user.role(), user.customerId()))
                // Only signed-in users get here, and the services' JSON bodies are small.
                .method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(
                        request.getInputStream().readAllBytes()));
        REQUEST_HEADERS.forEach(name -> {
            if (request.getHeader(name) != null) {
                forwarded.header(name, request.getHeader(name));
            }
        });

        // This HttpClient is not instrumented, so the call gets its own span here, and the service
        // receives the trace in a traceparent header and continues it.
        String host = URI.create(serviceUrl).getHost();
        Span.Builder builder = tracer.spanBuilder()
                .name(request.getMethod() + " " + host)
                .kind(Span.Kind.CLIENT)
                .remoteServiceName(host)
                .tag("http.request.method", request.getMethod())
                .tag("server.address", host);
        TraceContext parent = tracer.currentTraceContext().context();
        if (parent != null) {
            builder.setParent(parent);
        }
        Span call = builder.start();
        propagator.inject(call.context(), forwarded, HttpRequest.Builder::header);
        try (Tracer.SpanInScope scope = tracer.withSpan(call)) {
            HttpResponse<byte[]> answer = httpClient.send(forwarded.build(), HttpResponse.BodyHandlers.ofByteArray());
            call.tag("http.response.status_code", String.valueOf(answer.statusCode()));
            response.setStatus(answer.statusCode());
            RESPONSE_HEADERS.forEach(name ->
                    answer.headers().firstValue(name).ifPresent(value -> response.setHeader(name, value)));
            response.getOutputStream().write(answer.body());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            call.error(e);
            log.warn("Could not connect to {}: {}", serviceUrl, e.toString());
            Problems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Part of the system is temporarily unavailable. Nothing was changed; please try again shortly.");
        } catch (IOException e) {
            // The request went out but no answer came back, so it may have been carried out.
            call.error(e);
            log.warn("No answer from {}: {}", serviceUrl, e.toString());
            Problems.write(response, HttpStatus.GATEWAY_TIMEOUT, request.getHeader(IdempotencyFilter.KEY_HEADER) != null
                    ? "No answer was received. It is safe to send this again with the same Idempotency-Key."
                    : "No answer was received, so this may or may not have been applied. Check before trying again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Problems.write(response, HttpStatus.SERVICE_UNAVAILABLE, "The server is shutting down.");
        } finally {
            call.end();
        }
    }

    private String serviceFor(String path) {
        if (path.startsWith("/api/auth/") || CUSTOMER_LOGIN.matcher(path).matches()) {
            return authUrl;
        }
        if (path.equals("/api/customers") || path.startsWith("/api/customers/")
                || path.equals("/api/help-requests") || path.startsWith("/api/help-requests/")) {
            return customerUrl;
        }
        if (path.equals("/api/accounts") || path.startsWith("/api/accounts/")) {
            return accountUrl;
        }
        return null;
    }
}
