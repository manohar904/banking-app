package com.bank.auth.dto;

import com.bank.auth.AppUser;
import com.bank.auth.Role;

public record CurrentUserResponse(String email, Role role, Long customerId) {

    public static CurrentUserResponse from(AppUser user) {
        return new CurrentUserResponse(user.getEmail(), user.getRole(), user.getCustomerId());
    }
}
