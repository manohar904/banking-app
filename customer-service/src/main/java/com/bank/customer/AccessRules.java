package com.bank.customer;

import com.bank.common.security.Callers;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Who may read what, used from @PreAuthorize. */
@Component("access")
public class AccessRules {

    public boolean canViewCustomer(Authentication authentication, Long customerId) {
        return Callers.canViewCustomer(authentication, customerId);
    }
}
