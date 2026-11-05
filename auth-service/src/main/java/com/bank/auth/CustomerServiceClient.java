package com.bank.auth;

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

    public String emailOf(Long customerId) {
        try {
            return client.get().uri("/internal/customers/{id}", customerId)
                    .retrieve().body(CustomerEmail.class).email();
        } catch (HttpClientErrorException.NotFound e) {
            throw new ResourceNotFoundException("Customer " + customerId + " not found.");
        }
    }

    private record CustomerEmail(String email) { }
}
