package com.bank.auth;

import java.util.List;
import lombok.Getter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** A sign-in account while its password is checked. */
@Getter
public class BankUserDetails extends User {

    private final Long userId;
    private final Long customerId;

    public BankUserDetails(Long userId, String email, String passwordHash, Role role, Long customerId) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        this.userId = userId;
        this.customerId = customerId;
    }
}
