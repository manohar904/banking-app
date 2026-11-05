package com.bank.auth;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class LoginService {

    /** BCrypt refuses longer input, and a multi-byte password can pass a character-count check. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final AppUserRepository userRepository;
    private final CustomerServiceClient customerService;
    private final PasswordEncoder passwordEncoder;

    /** Creates the customer's login, or resets the password if they already have one. */
    public void setCustomerPassword(Long customerId, String password) {
        String email = customerService.emailOf(customerId);
        String hash = encode(password);
        AppUser user = userRepository.findByCustomerId(customerId)
                .orElseGet(() -> AppUser.customer(customerId, email, hash));
        user.changePasswordHash(hash);
        // Flushed here so an email already used by a staff login is reported as a 409.
        userRepository.saveAndFlush(user);
    }

    /** Flushed here so an email already used by a staff login is reported as a 409. */
    public void changeCustomerEmail(Long customerId, String email) {
        userRepository.findByCustomerId(customerId).ifPresent(user -> {
            user.changeEmail(email);
            userRepository.saveAndFlush(user);
        });
    }

    public void deleteCustomerLogin(Long customerId) {
        userRepository.findByCustomerId(customerId).ifPresent(userRepository::delete);
    }

    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in."));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is incorrect.");
        }
        user.changePasswordHash(encode(newPassword));
    }

    String encode(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The password is too long.");
        }
        return passwordEncoder.encode(password);
    }
}
