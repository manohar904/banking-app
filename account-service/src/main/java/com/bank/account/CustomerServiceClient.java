package com.bank.account;

import com.bank.common.ResourceNotFoundException;
import com.bank.common.security.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class CustomerServiceClient {

    private final RestClient client;

    CustomerServiceClient(InternalClients clients, @Value("${bank.services.customer-url}") String baseUrl) {
        this.client = clients.to(baseUrl);
    }

    public void requireExists(Long customerId) {
        try {
            client.get().uri("/internal/customers/{id}", customerId).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Customer " + customerId + " not found.");
        }
    }
}
