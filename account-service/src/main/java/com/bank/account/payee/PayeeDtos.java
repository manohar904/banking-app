package com.bank.account.payee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** What the payees API takes and gives back. */
public final class PayeeDtos {

    private PayeeDtos() {
    }

    /** customerId is for staff saving a payee for a customer; a customer's own is used otherwise. */
    public record NewPayee(
            Long customerId,
            @NotBlank @Size(max = 40) String name,
            @NotNull @Pattern(regexp = "[0-9]{12}", message = "must be a 12-digit account number") String accountNumber) {
    }

    public record PayeeResponse(Long id, Long customerId, String name, String accountNumber, Instant createdAt) {

        static PayeeResponse from(Payee payee) {
            return new PayeeResponse(payee.getId(), payee.getCustomerId(), payee.getName(), payee.getAccountNumber(),
                    payee.getCreatedAt());
        }
    }
}
