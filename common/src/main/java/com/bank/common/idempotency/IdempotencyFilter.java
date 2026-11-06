package com.bank.common.idempotency;

import com.bank.common.security.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes a write safe to send again. A request with an {@code Idempotency-Key} header is carried out
 * once: its answer is stored in the same database transaction as the change, and a repeat with the
 * same key gets that answer back, marked {@code Idempotent-Replayed: true}, without doing anything.
 *
 * <ul>
 *   <li>The key is claimed before the work starts. A repeat sent while the first is still running
 *       waits for it on the database row, then gets its answer.</li>
 *   <li>Only successful answers are stored. A failure changed nothing, so the same key can be tried
 *       again, for example after a 409 or once the customer has enough money.</li>
 *   <li>Keys belong to the caller and are kept for 24 hours. Reusing one for a different request
 *       is refused with a 422.</li>
 * </ul>
 *
 * Runs after Spring Security, so it knows the caller, and needs the {@code idempotency_keys} table.
 * Created by {@link IdempotencyConfig}.
 */
@Slf4j
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");
    private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final List<String> STORED_HEADERS = List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.LOCATION);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public IdempotencyFilter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(KEY_HEADER) == null
                || !WRITES.contains(request.getMethod())
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(KEY_HEADER);
        if (!VALID_KEY.matcher(key).matches()) {
            Problems.write(response, HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1 to 100 letters, digits or the characters _ . : -");
            return;
        }
        String caller = callerOf(SecurityContextHolder.getContext().getAuthentication());
        if (caller == null) {
            // Not signed in: let the security rules answer as they would without a key.
            chain.doFilter(request, response);
            return;
        }

        CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
        String fingerprint = fingerprint(request, cachedRequest.body);
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        Outcome outcome;
        try {
            outcome = transactions.execute(status -> {
                // Waits here while another request holding the same key is still running.
                int claimed = jdbc.update("""
                        insert into idempotency_keys (caller, idem_key, fingerprint) values (?, ?, ?)
                        on conflict do nothing""", caller, key, fingerprint);
                if (claimed == 0) {
                    status.setRollbackOnly();
                    return Outcome.ALREADY_DONE;
                }
                run(chain, cachedRequest, wrapped);
                if (wrapped.getStatus() >= 400) {
                    status.setRollbackOnly();
                    return Outcome.FAILED;
                }
                if (status.isRollbackOnly()) {
                    // Without this filter, the service's own commit would have failed here too.
                    throw new IllegalStateException("The changes behind a successful answer were rolled back");
                }
                jdbc.update("""
                        update idempotency_keys
                        set response_status = ?, response_content_type = ?, response_location = ?, response_body = ?
                        where caller = ? and idem_key = ?""",
                        wrapped.getStatus(), wrapped.getContentType(), wrapped.getHeader(HttpHeaders.LOCATION),
                        wrapped.getContentAsByteArray(), caller, key);
                return Outcome.DONE;
            });
        } catch (FilterFailure e) {
            e.rethrow();
            return;
        } catch (RuntimeException e) {
            // The work was carried out but could not be committed, so it was rolled back.
            log.warn("Could not commit a request with an Idempotency-Key: {}", e.toString());
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            Problems.write(response, HttpStatus.INTERNAL_SERVER_ERROR,
                    "This could not be saved, so nothing was changed. Send it again with the same Idempotency-Key.");
            return;
        }

        if (outcome == Outcome.ALREADY_DONE) {
            replay(caller, key, fingerprint, response);
        } else {
            wrapped.copyBodyToResponse();
        }
    }

    /** Sends the stored answer, unless the key was used for something else. */
    private void replay(String caller, String key, String fingerprint, HttpServletResponse response)
            throws IOException {
        Map<String, Object> stored = jdbc.queryForMap("""
                select fingerprint, response_status, response_content_type, response_location, response_body
                from idempotency_keys where caller = ? and idem_key = ?""", caller, key);
        if (!fingerprint.equals(stored.get("fingerprint"))) {
            Problems.write(response, HttpStatus.UNPROCESSABLE_CONTENT,
                    "This Idempotency-Key was already used for a different request. Use a new key for each change.");
            return;
        }
        response.setStatus((Integer) stored.get("response_status"));
        response.setHeader(REPLAYED_HEADER, "true");
        String[] headerValues = {(String) stored.get("response_content_type"), (String) stored.get("response_location")};
        for (int i = 0; i < STORED_HEADERS.size(); i++) {
            if (headerValues[i] != null) {
                response.setHeader(STORED_HEADERS.get(i), headerValues[i]);
            }
        }
        byte[] body = (byte[]) stored.get("response_body");
        if (body != null && body.length > 0) {
            response.getOutputStream().write(body);
        }
    }

    /** Answers older than this cannot be replayed: a repeat after that is carried out again. */
    @Scheduled(fixedDelayString = "${bank.idempotency.cleanup-interval:3600000}", initialDelay = 60_000)
    public void removeExpiredKeys() {
        int removed = jdbc.update("delete from idempotency_keys where created_at < now() - interval '24 hours'");
        if (removed > 0) {
            log.info("Removed {} expired idempotency keys", removed);
        }
    }

    private static void run(FilterChain chain, HttpServletRequest request, HttpServletResponse response) {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException e) {
            throw new FilterFailure(e);
        }
    }

    /** Keys are per person, so one caller can never see another's stored answer. */
    private static String callerOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token ? token.getToken().getSubject() : null;
    }

    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            String query = request.getQueryString() == null ? "" : "?" + request.getQueryString();
            sha256.update((request.getMethod() + " " + request.getRequestURI() + query + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            sha256.update(body);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private enum Outcome { DONE, FAILED, ALREADY_DONE }

    /** Carries a checked exception out of the transaction callback, which then rolls back. */
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }

        void rethrow() throws IOException, ServletException {
            if (getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) getCause();
        }
    }

    /** The body is read once for the fingerprint, then handed to the controller again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), Charset.forName(encoding)));
        }
    }
}
