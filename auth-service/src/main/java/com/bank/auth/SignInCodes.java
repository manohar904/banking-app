package com.bank.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Six-digit codes sent by email. Each works once, for a few minutes, and is thrown away after five
 * wrong tries. Only a hash is stored, salted with the code's id so equal codes don't hash alike.
 */
@Slf4j
@Service
public class SignInCodes {

    public enum Purpose {
        SIGN_IN,
        TURN_ON
    }

    static final int MINUTES = 10;
    static final int MAX_TRIES = 5;

    static final String EXPIRED = "This code has expired or was entered wrongly too many times. Please start again.";

    private final JdbcTemplate jdbc;
    private final SignInCodeMailer mailer;
    private final SecureRandom random = new SecureRandom();

    SignInCodes(JdbcTemplate jdbc, SignInCodeMailer mailer) {
        this.jdbc = jdbc;
        this.mailer = mailer;
    }

    public boolean canSend() {
        return mailer.canSend();
    }

    /** Sends a new code, replacing any earlier one for the same purpose. Returns the code's id. */
    @Transactional
    public UUID send(AppUser user, Purpose purpose) {
        if (!mailer.canSend()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email is not set up for this bank, so codes cannot be sent.");
        }
        jdbc.update("delete from sign_in_codes where expires_at <= now()");
        UUID id = UUID.randomUUID();
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        jdbc.update("""
                insert into sign_in_codes (id, user_id, purpose, code_hash, expires_at)
                values (?, ?, ?, ?, now() + make_interval(mins => ?))
                on conflict (user_id, purpose) do update
                set id = excluded.id, code_hash = excluded.code_hash, attempts = 0, expires_at = excluded.expires_at
                """, id, user.getId(), purpose.name(), hash(id, code), MINUTES);
        try {
            mailer.send(user.getEmail(), code, purpose, MINUTES);
        } catch (MailException e) {
            // Rolls back the new code too: there's no point keeping one that never arrived.
            log.warn("A sign-in code could not be sent: {}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The code could not be sent by email. Please try again shortly.");
        }
        return id;
    }

    /** Checks a code by its id. Returns the user's id, or throws 401 (wrong) or 410 (start again). */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public long check(UUID id, Purpose purpose, String code) {
        return check("id = ?", id, purpose, code);
    }

    /** Checks the person's latest code for this purpose. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public long checkFor(long userId, Purpose purpose, String code) {
        return check("user_id = ?", userId, purpose, code);
    }

    private long check(String which, Object key, Purpose purpose, String code) {
        List<Row> rows = jdbc.query("select id, user_id, code_hash, attempts from sign_in_codes where " + which
                        + " and purpose = ? and expires_at > now() for update",
                (row, n) -> new Row(row.getObject(1, UUID.class), row.getLong(2), row.getString(3), row.getInt(4)),
                key, purpose.name());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, EXPIRED);
        }
        Row row = rows.getFirst();
        String given = code == null ? "" : code.strip();
        if (given.matches("[0-9]{6}") && MessageDigest.isEqual(
                hash(row.id(), given).getBytes(StandardCharsets.US_ASCII), row.codeHash().getBytes(StandardCharsets.US_ASCII))) {
            jdbc.update("delete from sign_in_codes where id = ?", row.id());
            return row.userId();
        }
        if (row.attempts() + 1 >= MAX_TRIES) {
            jdbc.update("delete from sign_in_codes where id = ?", row.id());
            throw new ResponseStatusException(HttpStatus.GONE, EXPIRED);
        }
        jdbc.update("update sign_in_codes set attempts = attempts + 1 where id = ?", row.id());
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "That code is not right. Check the email and try again.");
    }

    private record Row(UUID id, long userId, String codeHash, int attempts) {
    }

    private static String hash(UUID id, String code) {
        return SignInLimiter.sha256(id + ":" + code);
    }
}
