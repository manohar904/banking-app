package com.bank.account;

import com.bank.common.security.Callers;
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
        return Callers.canViewCustomer(authentication, customerId);
    }

    public boolean canViewAccount(Authentication authentication, Long accountId) {
        if (Callers.isStaff(authentication)) {
            return true;
        }
        Long ownCustomerId = Callers.customerIdOf(authentication);
        return ownCustomerId != null && accountRepository.existsByIdAndCustomerId(accountId, ownCustomerId);
    }
}
