package com.bank.auth;

import com.bank.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Who may read what, used from @PreAuthorize. Staff may read everything; a customer only their own
 * records. An unknown id and someone else's id are both refused, so a customer cannot use the
 * response to find out which ids exist.
 */
@Component("access")
@RequiredArgsConstructor
public class AccessRules {

    private final AccountRepository accountRepository;

    public boolean canViewCustomer(Authentication authentication, Long customerId) {
        return isStaff(authentication)
                || (customerId != null && customerId.equals(customerIdOf(authentication)));
    }

    public boolean canViewAccount(Authentication authentication, Long accountId) {
        if (isStaff(authentication)) {
            return true;
        }
        Long ownCustomerId = customerIdOf(authentication);
        return ownCustomerId != null && accountRepository.existsByIdAndCustomerId(accountId, ownCustomerId);
    }

    private static boolean isStaff(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_STAFF".equals(authority.getAuthority()));
    }

    private static Long customerIdOf(Authentication authentication) {
        return authentication.getPrincipal() instanceof BankUserDetails user ? user.getCustomerId() : null;
    }
}
