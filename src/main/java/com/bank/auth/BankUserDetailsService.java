package com.bank.auth;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BankUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        // Emails are stored in lowercase, so sign-in does not depend on how they are typed.
        return userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(user -> new BankUserDetails(user.getId(), user.getEmail(), user.getPasswordHash(),
                        user.getRole(), user.getCustomerId()))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user."));
    }
}
