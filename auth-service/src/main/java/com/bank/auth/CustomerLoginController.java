package com.bank.auth;

import com.bank.auth.dto.SetPasswordRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CustomerLoginController {

    private final LoginService loginService;

    /** Creates the customer's login, or resets its password. They sign in with their email. */
    @PutMapping("/api/customers/{id}/login")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setLogin(@PathVariable Long id, @Valid @RequestBody SetPasswordRequest request) {
        loginService.setCustomerPassword(id, request.password());
    }
}
