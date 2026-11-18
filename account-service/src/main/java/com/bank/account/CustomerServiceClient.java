package com.bank.account;

import com.bank.common.ResourceNotFoundException;
import com.bank.common.security.InternalClients;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class CustomerServiceClient {

    private record Name(String firstName, String lastName) {
    }

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

    /** The customer's full name, or empty if it can't be had right now. Statements are made without it then. */
    public Optional<String> nameOf(Long customerId) {
        try {
            Name name = client.get().uri("/internal/customers/{id}", customerId).retrieve().body(Name.class);
            return Optional.ofNullable(name).map(found -> (found.firstName() + " " + found.lastName()).strip());
        } catch (RestClientException e) {
            log.warn("Could not look up customer {} for a statement: {}", customerId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
