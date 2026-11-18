package com.bank.auth;

import com.bank.auth.dto.ConfirmPasswordResetRequest;
import com.bank.auth.dto.CredentialsRequest;
import com.bank.auth.dto.EmailRequest;
import com.bank.auth.dto.CodeCheckRequest;
import com.bank.auth.dto.CodeSentResponse;
import com.bank.auth.dto.VerifiedUserResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalLoginController {

    private final AuthenticationManager credentialChecker;
    private final LoginService loginService;
    private final SignInLimiter signInLimiter;
    private final PasswordResetService passwordResets;
    private final MeterRegistry metrics;
    private final AppUserRepository users;
    private final SignInCodes codes;

    /**
     * Checks a password for the gateway, which then holds the session. For someone with two-step
     * sign-in on, a right password answers 202 with the id of a code just emailed to them, and the
     * gateway finishes the sign-in with /sign-in-codes. Until then the attempt still counts towards the
     * email's limit, so knowing the password does not allow unlimited codes.
     */
    @PostMapping("/authentications")
    public ResponseEntity<?> authenticate(@RequestBody CredentialsRequest request) {
        // The address first: an attempt it refuses is not counted against the email.
        if (!signInLimiter.allowAttemptFrom(request.clientAddress())) {
            metrics.counter("bank.signins", "outcome", "limited-address").increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts.");
        }
        if (!signInLimiter.allowAttempt(request.email())) {
            metrics.counter("bank.signins", "outcome", "limited").increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts.");
        }
        BankUserDetails user;
        try {
            user = (BankUserDetails) credentialChecker.authenticate(
                            UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()))
                    .getPrincipal();
        } catch (AuthenticationException e) {
            metrics.counter("bank.signins", "outcome", "failure").increment();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        signInLimiter.forgiveAttemptFrom(request.clientAddress());
        AppUser login = users.findById(user.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect email or password."));
        if (login.isTwoStepEnabled()) {
            if (codes.canSend()) {
                UUID codeId = codes.send(login, SignInCodes.Purpose.SIGN_IN);
                metrics.counter("bank.signins", "outcome", "code-sent").increment();
                return ResponseEntity.accepted().body(new CodeSentResponse(codeId, CodeSentResponse.hide(login.getEmail())));
            }
            // Email was turned off after this person turned two-step on. Letting them in on the password
            // alone is better than locking everyone out, staff included.
            log.warn("Two-step sign-in is on for user {} but email is not set up, so no code was asked for.",
                    login.getId());
        }
        signInLimiter.clear(request.email());
        metrics.counter("bank.signins", "outcome", "success").increment();
        return ResponseEntity.ok(VerifiedUserResponse.from(user));
    }

    /** Finishes a two-step sign-in: 401 for a wrong code, 410 when it must be started again. */
    @PostMapping("/sign-in-codes")
    public VerifiedUserResponse checkSignInCode(@RequestBody CodeCheckRequest request) {
        long userId;
        try {
            userId = codes.check(request.codeId(), SignInCodes.Purpose.SIGN_IN, request.code());
        } catch (ResponseStatusException e) {
            metrics.counter("bank.signins", "outcome", "wrong-code").increment();
            throw e;
        }
        AppUser login = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, SignInCodes.EXPIRED));
        signInLimiter.clear(login.getEmail());
        metrics.counter("bank.signins", "outcome", "success").increment();
        return VerifiedUserResponse.of(login);
    }

    /** The same answer whether or not the email has an account. */
    @PostMapping("/password-reset-requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestPasswordReset(@Valid @RequestBody EmailRequest request) {
        passwordResets.requestReset(request.email());
    }

    @PostMapping("/password-resets")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPasswordReset(@Valid @RequestBody ConfirmPasswordResetRequest request) {
        passwordResets.confirmReset(request.token(), request.newPassword());
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
