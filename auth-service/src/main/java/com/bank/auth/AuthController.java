package com.bank.auth;

import com.bank.auth.dto.ChangePasswordRequest;
import com.bank.auth.dto.CurrentUserResponse;
import com.bank.common.security.Callers;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sign-in and sign-out themselves are handled by the gateway, which holds the session. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

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

    private static Long requireUserId(Jwt token) {
        Long userId = Callers.userIdOf(token);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        }
        return userId;
    }
}
