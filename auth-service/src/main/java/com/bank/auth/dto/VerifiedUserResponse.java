package com.bank.auth.dto;

import com.bank.auth.BankUserDetails;

/** What the gateway keeps in the session. Never includes the password hash. */
public record VerifiedUserResponse(long userId, String email, String role, Long customerId) {

    public static VerifiedUserResponse from(BankUserDetails user) {
        String role = user.getAuthorities().iterator().next().getAuthority().substring("ROLE_".length());
        return new VerifiedUserResponse(user.getUserId(), user.getUsername(), role, user.getCustomerId());
    }
}
