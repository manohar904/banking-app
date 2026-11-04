package com.bank.account.dto;

import com.bank.account.AccountType;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
        @NotNull Long customerId,
        @NotNull AccountType type) {
}
