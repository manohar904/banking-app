package com.bank.auth;

import com.bank.auth.dto.ChangePasswordRequest;
import com.bank.auth.dto.CurrentUserResponse;
import com.bank.common.security.Callers;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sign-in and sign-out themselves are handled by the gateway, which holds the session. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_ACTORS = 100;

    private final AppUserRepository userRepository;
    private final LoginService loginService;

    /** Read from the database, so a changed email shows and a removed login is signed out. */
    @GetMapping("/me")
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt token) {
        return userRepository.findById(requireUserId(token))
                .map(CurrentUserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal Jwt token,
            @Valid @RequestBody ChangePasswordRequest request) {
        loginService.changeOwnPassword(requireUserId(token), request.currentPassword(), request.newPassword());
    }

    /** Emails for the user ids in the audit log. An id with no login any more is left out. */
    @PreAuthorize("hasRole('STAFF')")
    @GetMapping("/audit/actors")
    public Map<Long, String> auditActors(@RequestParam List<Long> ids) {
        if (ids.size() > MAX_ACTORS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ask for at most " + MAX_ACTORS + " ids.");
        }
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getEmail));
    }

    private static Long requireUserId(Jwt token) {
        Long userId = Callers.userIdOf(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }
        return userId;
    }
}
