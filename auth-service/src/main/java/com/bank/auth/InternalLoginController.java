package com.bank.auth;

import com.bank.auth.dto.CredentialsRequest;
import com.bank.auth.dto.EmailRequest;
import com.bank.auth.dto.VerifiedUserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** For other services. The gateway does not route /internal, and it requires a service token. */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalLoginController {

    private final AuthenticationManager credentialChecker;
    private final LoginService loginService;

    /** Checks a password for the gateway, which then holds the session. */
    @PostMapping("/authentications")
    public VerifiedUserResponse authenticate(@RequestBody CredentialsRequest request) {
        try {
            BankUserDetails user = (BankUserDetails) credentialChecker.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()))
                    .getPrincipal();
            return VerifiedUserResponse.from(user);
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
    }

    @PutMapping("/logins/{customerId}/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeEmail(@PathVariable Long customerId, @Valid @RequestBody EmailRequest request) {
        loginService.changeCustomerEmail(customerId, request.email());
    }

    @DeleteMapping("/logins/{customerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long customerId) {
        loginService.deleteCustomerLogin(customerId);
    }
}
