package com.bank.customer;

import com.bank.common.ConflictException;
import com.bank.common.security.InternalClients;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class AuthServiceClient {

    private final RestClient client;

    AuthServiceClient(InternalClients clients, @Value("${bank.services.auth-url}") String baseUrl) {
        this.client = clients.to(baseUrl);
    }

    /** Does nothing if the customer has no login. Safe to repeat. */
    public void changeLoginEmail(Long customerId, String email) {
        try {
            client.put().uri("/internal/logins/{customerId}/email", customerId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ConflictException("This email is already used by another login.");
        }
    }

    /** Does nothing if the customer has no login. Safe to repeat. */
    public void deleteLogin(Long customerId) {
        client.delete().uri("/internal/logins/{customerId}", customerId).retrieve().toBodilessEntity();
    }
}
