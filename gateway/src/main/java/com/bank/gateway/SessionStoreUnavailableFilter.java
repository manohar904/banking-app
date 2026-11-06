package com.bank.gateway;

import com.bank.common.security.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While Redis cannot be reached, nobody's session can be loaded. Answers that with a 503 in the same
 * form as the gateway's other errors, instead of a server error page. Sits just outside Spring
 * Session's filter, which is where the failure surfaces.
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER - 1)
@Slf4j
public class SessionStoreUnavailableFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException e) {
            if (!causedByUnreachableRedis(e) || response.isCommitted()) {
                throw e;
            }
            log.warn("Could not reach Redis for the session: {}", e.toString());
            response.reset();
            // Usually the session could not even be loaded, so nothing was forwarded. Rarely it is
            // saving the session afterwards that fails, hence the advice to check.
            Problems.write(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "Signing in and sessions are temporarily unavailable. Please try again shortly. "
                            + "If you were making a change, check whether it was applied.");
        }
    }

    private static boolean causedByUnreachableRedis(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof QueryTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
