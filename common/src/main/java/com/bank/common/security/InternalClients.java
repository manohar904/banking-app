package com.bank.common.security;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the clients used for calls between services. Timeouts are short because the caller is
 * usually holding a user's request, and often a database transaction, while it waits.
 */
@Component
@RequiredArgsConstructor
public class InternalClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final InternalTokens tokens;
    private final InternalTls tls;
    // Times each call and passes the trace on, so a request can be followed from service to service.
    private final ObservationRegistry observationRegistry;

    public RestClient to(String baseUrl) {
        HttpClient httpClient = tls.configure(HttpClient.newBuilder())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBearerAuth(tokens.forThisService());
                    return execution.execute(request, body);
                })
                .build();
    }
}
