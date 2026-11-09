package com.bank.customer.dto;

import com.bank.customer.Customer;
import java.time.Instant;

public record CustomerResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        boolean transactionEmailsEnabled,
        Instant createdAt,
        Instant updatedAt) {

    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getEmail(),
                customer.isTransactionEmailsEnabled(),
                customer.getCreatedAt(),
                customer.getUpdatedAt());
    }
}
