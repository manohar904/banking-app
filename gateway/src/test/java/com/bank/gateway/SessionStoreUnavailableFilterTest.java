package com.bank.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionStoreUnavailableFilterTest {

    private final SessionStoreUnavailableFilter filter = new SessionStoreUnavailableFilter();

    @Test
    void redisBeingUnreachableIsA503InTheUsualErrorFormat() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (request, res) -> {
            throw new IllegalStateException("wrapped", new RedisConnectionFailureException("Unable to connect"));
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("temporarily unavailable");
    }

    @Test
    void otherFailuresAreLeftAlone() {
        FilterChain chain = (request, res) -> {
            throw new IllegalStateException("something else");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .hasMessage("something else");
    }
}
