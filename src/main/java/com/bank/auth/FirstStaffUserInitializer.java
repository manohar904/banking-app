package com.bank.auth;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first staff login from BANK_ADMIN_EMAIL and BANK_ADMIN_PASSWORD. There is deliberately
 * no built-in default: a known password would give anyone full access to the bank's data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FirstStaffUserInitializer implements ApplicationRunner {

    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository userRepository;
    private final LoginService loginService;

    @Value("${bank.admin.email:}")
    private String email;

    @Value("${bank.admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.STAFF)) {
            return;
        }
        if (email.isBlank() || password.isBlank()) {
            log.warn("No staff login exists, so nobody can sign in. "
                    + "Set BANK_ADMIN_EMAIL and BANK_ADMIN_PASSWORD and restart to create one.");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "BANK_ADMIN_PASSWORD must have at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        userRepository.save(AppUser.staff(normalizedEmail, loginService.encode(password)));
        log.info("Created the first staff login for {}.", normalizedEmail);
    }
}
