package com.bank.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Limits password guessing: a few attempts per email in each window, then nothing is checked until
 * the window ends. Kept in the database so that it holds across restarts and more than one copy.
 *
 * <p>Emails nobody has an account for are limited too, so the limit does not reveal who has one.
 *
 * <p>Each browser address also gets a limit on failed attempts over all emails, so that one source
 * cannot try a few passwords on each of many accounts.
 */
@Component
public class SignInLimiter {

    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_RESET_REQUESTS = 3;
    private static final String RESET_REQUEST_PREFIX = "reset-request:";
    private static final String ADDRESS_PREFIX = "address:";
    private static final int WINDOW_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final int maxFailuresPerAddress;

    SignInLimiter(JdbcTemplate jdbc,
            @Value("${bank.sign-in.max-failures-per-address}") int maxFailuresPerAddress) {
        this.jdbc = jdbc;
        this.maxFailuresPerAddress = maxFailuresPerAddress;
    }

    /**
     * Counts the attempt before the password is checked, so that guesses sent at the same moment
     * cannot all get through. Returns false if this attempt is over the limit.
     */
    public boolean allowAttempt(String email) {
        return allow(hash(email), MAX_ATTEMPTS);
    }

    /**
     * Like allowAttempt, for the browser's address. Call forgiveAttemptFrom after a successful
     * sign-in, so that only failures count: many people signing in from one office are not limited.
     * Always true when the address is not known.
     */
    public boolean allowAttemptFrom(String clientAddress) {
        return isBlank(clientAddress) || allow(addressKey(clientAddress), maxFailuresPerAddress);
    }

    /** Takes back the count for one attempt that turned out to have the right password. */
    public void forgiveAttemptFrom(String clientAddress) {
        if (!isBlank(clientAddress)) {
            jdbc.update("update sign_in_attempts set attempts = greatest(attempts - 1, 0) where email_hash = ?",
                    addressKey(clientAddress));
        }
    }

    /** Counted separately from sign-ins. Stops someone flooding a person's inbox with reset emails. */
    public boolean allowResetRequest(String email) {
        return allow(hash(RESET_REQUEST_PREFIX + (email == null ? "" : email.trim())), MAX_RESET_REQUESTS);
    }

    private boolean allow(String key, int limit) {
        // Skip locked: another sign-in is already removing those rows.
        jdbc.update("""
                delete from sign_in_attempts where email_hash in (
                    select email_hash from sign_in_attempts
                    where window_started_at <= now() - make_interval(mins => ?)
                    for update skip locked)
                """, WINDOW_MINUTES);
        Integer attempts = jdbc.queryForObject("""
                insert into sign_in_attempts (email_hash, attempts, window_started_at) values (?, 1, now())
                on conflict (email_hash) do update set attempts = sign_in_attempts.attempts + 1
                returning attempts
                """, Integer.class, key);
        return attempts != null && attempts <= limit;
    }

    /** After a successful sign-in, or when staff set a new password. */
    public void clear(String email) {
        jdbc.update("delete from sign_in_attempts where email_hash = ?", hash(email));
    }

    /** Hashed like emails, so addresses are not stored either. */
    private static String addressKey(String clientAddress) {
        return sha256(ADDRESS_PREFIX + clientAddress.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(String email) {
        return sha256(email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
