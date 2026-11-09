package com.bank.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Where the money goes and how much. Amount limits match MoneyRequest. */
public record TransferRequest(
        @NotNull
        @Pattern(regexp = "\\d{12}", message = "must be a 12-digit account number")
        String toAccountNumber,

        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount) {
}
