package com.bank.gateway;

import com.bank.common.security.InternalClients;
import com.bank.common.security.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The second step of two-step sign-in. After a right password the session holds only which code was
 * emailed; this checks the code the person types and, if it is right, signs the session in.
 */
@Slf4j
@RestController
public class SignInCodeController {

    static final String PENDING = SignInCodeController.class.getName() + ".PENDING";
    /** A little longer than a code lasts, so the auth service is the one that says it expired. */
    private static final Duration PENDING_FOR = Duration.ofMinutes(15);
    private static final String START_AGAIN =
            "This code has expired or was entered wrongly too many times. Please sign in again.";

    /** Kept in the session between the two steps. Never sent to the browser. */
    record PendingSignIn(UUID codeId, Instant startedAt) implements Serializable {
    }

    record CodeRequest(String code) {
    }

    private final RestClient authService;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    SignInCodeController(InternalClients clients, @Value("${bank.services.auth-url}") String authUrl) {
        this.authService = clients.to(authUrl);
    }

    /** Called from the sign-in failure handler when the password was right and a code was sent. */
    static void codeSent(HttpServletRequest request, HttpServletResponse response, SignInCodeRequiredException sent)
            throws IOException {
        request.getSession(true).setAttribute(PENDING, new PendingSignIn(sent.codeId(), Instant.now()));
        response.setStatus(HttpStatus.ACCEPTED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"codeRequired\":true,\"sentTo\":\"%s\"}".formatted(json(sent.sentTo())));
    }

    @PostMapping("/api/auth/login/code")
    void checkCode(@RequestBody CodeRequest body, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        HttpSession session = request.getSession(false);
        PendingSignIn pending = session == null ? null
                : session.getAttribute(PENDING) instanceof PendingSignIn found ? found : null;
        if (pending == null || pending.startedAt().plus(PENDING_FOR).isBefore(Instant.now())) {
            if (session != null) {
                session.removeAttribute(PENDING);
            }
            Problems.write(response, HttpStatus.UNAUTHORIZED, START_AGAIN);
            return;
        }
        SignedInUser user;
        try {
            user = authService.post().uri("/internal/sign-in-codes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("codeId", pending.codeId(), "code", body.code() == null ? "" : body.code()))
                    .retrieve()
                    .body(SignedInUser.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            Problems.write(response, HttpStatus.UNAUTHORIZED, "That code is not right. Check the email and try again.");
            return;
        } catch (HttpClientErrorException.Gone e) {
            session.removeAttribute(PENDING);
            Problems.write(response, HttpStatus.UNAUTHORIZED, START_AGAIN);
            return;
        } catch (RestClientException e) {
            log.warn("Could not check a sign-in code: {}", e.toString());
            Problems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign-in is temporarily unavailable. Please try again shortly.");
            return;
        }
        session.removeAttribute(PENDING);
        // A new session id once signed in, as the first step does, so an id seen before can't be used.
        request.changeSessionId();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(AuthServiceAuthenticationProvider.signedIn(user));
        holder.setContext(context);
        contexts.saveContext(context, request, response);
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    private static String json(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
