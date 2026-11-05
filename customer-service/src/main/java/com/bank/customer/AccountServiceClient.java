package com.bank.customer;

import com.bank.common.ConflictException;
import com.bank.common.security.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class AccountServiceClient {

    private final RestClient client;

    AccountServiceClient(InternalClients clients, @Value("${bank.services.account-url}") String baseUrl) {
        this.client = clients.to(baseUrl);
    }

    /** Stops the customer getting new accounts. Refused while they still have any. Safe to repeat. */
    public void retireCustomer(Long customerId) {
        try {
            client.put().uri("/internal/retired-customers/{id}", customerId).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new ConflictException("A customer who still has accounts cannot be deleted.");
        }
    }
}
