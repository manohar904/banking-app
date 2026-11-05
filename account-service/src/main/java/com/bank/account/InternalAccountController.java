package com.bank.account;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** For other services. The gateway does not route /internal, and it requires a service token. */
@RestController
@RequestMapping("/internal/retired-customers")
@RequiredArgsConstructor
public class InternalAccountController {

    private final AccountService accountService;

    @PutMapping("/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable Long customerId) {
        accountService.retireCustomer(customerId);
    }
}
