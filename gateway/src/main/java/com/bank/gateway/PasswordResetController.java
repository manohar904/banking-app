package com.bank.gateway;

import com.bank.common.security.InternalClients;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * "Forgot password" is for people who cannot sign in, so unlike the rest of /api these have no session
 * behind them. They reach the auth service with this gateway's own token.
 */
@RestController
public class PasswordResetController {

    /** These requests are anonymous, so their size is limited: an email or a token and a password. */
    private static final int MAX_BODY_BYTES = 4096;

    private final RestClient authService;

    PasswordResetController(InternalClients clients, @Value("${bank.services.auth-url}") String authUrl) {
        this.authService = clients.to(authUrl);
    }

    @PostMapping("/api/auth/password-reset")
    ResponseEntity<?> request(HttpServletRequest request) throws IOException {
        return forward("/internal/password-reset-requests", request);
    }

    @PostMapping("/api/auth/password-reset/confirm")
    ResponseEntity<?> confirm(HttpServletRequest request) throws IOException {
        return forward("/internal/password-resets", request);
    }

    private ResponseEntity<?> forward(String path, HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "This request is too large.");
        }
        try {
            return authService.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((sent, answer) -> {
                        ResponseEntity.BodyBuilder response = ResponseEntity.status(answer.getStatusCode());
                        if (answer.getHeaders().getContentType() != null) {
                            response.contentType(answer.getHeaders().getContentType());
                        }
                        return response.body(answer.getBody().readAllBytes());
                    });
        } catch (RestClientException e) {
            return problem(HttpStatus.SERVICE_UNAVAILABLE, "This is temporarily unavailable. Please try again shortly.");
        }
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
