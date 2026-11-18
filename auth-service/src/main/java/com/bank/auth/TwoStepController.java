package com.bank.auth;

import com.bank.auth.dto.TwoStepRequests.Code;
import com.bank.auth.dto.TwoStepRequests.Status;
import com.bank.auth.dto.TwoStepRequests.TurnOff;
import com.bank.common.events.DomainEvents;
import com.bank.common.security.Callers;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Each person's own two-step sign-in setting. Turning it on takes a code sent to their email, which
 * shows the email arrives before they come to depend on it. Turning it off takes their password.
 * Staff setting a customer's password turns it off too, as the way back in for a customer who lost
 * access to their email.
 */
@RestController
@RequestMapping("/api/auth/two-step")
@RequiredArgsConstructor
public class TwoStepController {

    private final AppUserRepository users;
    private final SignInCodes codes;
    private final PasswordEncoder passwordEncoder;
    private final DomainEvents events;

    @GetMapping
    public Status status(@AuthenticationPrincipal Jwt token) {
        AppUser user = me(token);
        return new Status(user.isTwoStepEnabled(), codes.canSend(), user.getEmail());
    }

    /** Emails a code to enter in /confirm. */
    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void start(@AuthenticationPrincipal Jwt token) {
        codes.send(me(token), SignInCodes.Purpose.TURN_ON);
    }

    @PostMapping("/confirm")
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Status confirm(@AuthenticationPrincipal Jwt token, @Valid @RequestBody Code request) {
        AppUser user = me(token);
        try {
            codes.checkFor(user.getId(), SignInCodes.Purpose.TURN_ON, request.code());
        } catch (ResponseStatusException e) {
            // A 401 would tell the app the session had ended. The person is signed in; the code is wrong.
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getReason());
            }
            throw e;
        }
        user.turnOnTwoStep();
        users.saveAndFlush(user);
        events.record("TWO_STEP_TURNED_ON", user.getId(), Map.of());
        return new Status(true, codes.canSend(), user.getEmail());
    }

    @PostMapping("/turn-off")
    @Transactional
    public Status turnOff(@AuthenticationPrincipal Jwt token, @Valid @RequestBody TurnOff request) {
        AppUser user = me(token);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The password is incorrect.");
        }
        if (user.isTwoStepEnabled()) {
            user.turnOffTwoStep();
            users.saveAndFlush(user);
            events.record("TWO_STEP_TURNED_OFF", user.getId(), Map.of());
        }
        return new Status(false, codes.canSend(), user.getEmail());
    }

    private AppUser me(Jwt token) {
        Long userId = Callers.userIdOf(token);
        return (userId == null ? java.util.Optional.<AppUser>empty() : users.findById(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
    }
}
