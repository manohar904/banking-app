package com.bank.auth;

import com.bank.common.events.DomainEvents;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** "Forgot password": a single-use link, sent to the account's email, that expires after 30 minutes. */
@Service
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int LINK_MINUTES = 30;
    private static final String INVALID_LINK = "This reset link is invalid or has expired. Please request a new one.";

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final LoginService loginService;
    private final SignInLimiter limiter;
    private final PasswordResetMailer mailer;
    private final DomainEvents events;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    PasswordResetService(JdbcTemplate jdbc, AppUserRepository users, LoginService loginService, SignInLimiter limiter,
            PasswordResetMailer mailer, DomainEvents events,
            @Value("${bank.password-reset.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.users = users;
        this.loginService = loginService;
        this.limiter = limiter;
        this.mailer = mailer;
        this.events = events;
        this.baseUrl = baseUrl;
    }

    /**
     * Always ends quietly, so the caller cannot tell whether the email has an account. The base URL
     * comes from configuration, never the request, so a forged Host header cannot redirect the link.
     */
    public void requestReset(String email) {
        if (!limiter.allowResetRequest(email)) {
            return;
        }
        jdbc.update("delete from password_reset_tokens where expires_at <= now()");
        users.findByEmail(email.trim().toLowerCase(Locale.ROOT)).ifPresent(user -> {
            byte[] bytes = new byte[TOKEN_BYTES];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            jdbc.update("""
                    insert into password_reset_tokens (token_hash, user_id, expires_at)
                    values (?, ?, now() + make_interval(mins => ?))
                    on conflict (user_id) do update
                    set token_hash = excluded.token_hash, expires_at = excluded.expires_at
                    """, SignInLimiter.sha256(token), user.getId(), LINK_MINUTES);
            // In the fragment, so the token is not sent to servers or written to access logs.
            mailer.send(user.getEmail(), baseUrl + "/#reset=" + token);
        });
    }

    /**
     * Deleting the row is what uses the link, so two attempts at once cannot both succeed. If the new
     * password is refused, the transaction rolls back and the link still works.
     */
    @Transactional
    public void confirmReset(String token, String newPassword) {
        List<Long> userIds = jdbc.queryForList(
                "delete from password_reset_tokens where token_hash = ? and expires_at > now() returning user_id",
                Long.class, SignInLimiter.sha256(token));
        if (userIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_LINK);
        }
        AppUser user = users.findById(userIds.getFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_LINK));
        user.changePasswordHash(loginService.encode(newPassword));
        limiter.clear(user.getEmail());
        events.record("PASSWORD_RESET", user.getId(), Map.of());
    }
}
