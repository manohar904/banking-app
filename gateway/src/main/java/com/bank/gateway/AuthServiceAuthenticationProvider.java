package com.bank.gateway;

import com.bank.common.security.InternalClients;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The auth service checks the password. The session it results in lives here in the gateway. */
@Component
public class AuthServiceAuthenticationProvider implements AuthenticationProvider {

    private final RestClient authService;

    AuthServiceAuthenticationProvider(InternalClients clients, @Value("${bank.services.auth-url}") String authUrl) {
        this.authService = clients.to(authUrl);
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        try {
            SignedInUser user = authService.post().uri("/internal/authentications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", authentication.getName(),
                            "password", String.valueOf(authentication.getCredentials()),
                            "clientAddress", clientAddress(authentication)))
                    .retrieve()
                    .body(SignedInUser.class);
            return UsernamePasswordAuthenticationToken.authenticated(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BadCredentialsException("Incorrect email or password.");
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new LockedException("Too many sign-in attempts.");
        } catch (RestClientException e) {
            throw new AuthenticationServiceException("The auth service could not check the password.", e);
        }
    }

    /**
     * The browser's address, which the auth service limits failed sign-ins by. Behind a proxy this is
     * the proxy's address unless server.forward-headers-strategy is set; see README.md.
     */
    private static String clientAddress(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                && details.getRemoteAddress() != null ? details.getRemoteAddress() : "";
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
