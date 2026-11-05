package com.bank.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Reads who is calling from the internal token. */
public final class Callers {

    private Callers() {
    }

    public static boolean isStaff(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_STAFF".equals(authority.getAuthority()));
    }

    /** Null for anyone who is not a customer. */
    public static Long customerIdOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                ? asLong(token.getToken(), InternalTokens.CUSTOMER_ID_CLAIM)
                : null;
    }

    /** Null for a token that was not issued for a person. */
    public static Long userIdOf(Jwt token) {
        return asLong(token, InternalTokens.USER_ID_CLAIM);
    }

    /** Staff may see everyone; a customer only themselves. */
    public static boolean canViewCustomer(Authentication authentication, Long customerId) {
        return isStaff(authentication)
                || (customerId != null && customerId.equals(customerIdOf(authentication)));
    }

    private static Long asLong(Jwt token, String claim) {
        Number value = token.getClaim(claim);
        return value == null ? null : value.longValue();
    }
}
