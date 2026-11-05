package com.bank.account.dto;

import com.bank.account.Account;
import com.bank.account.AccountType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String accountNumber,
        AccountType type,
        BigDecimal balance,
        Long customerId,
        Instant createdAt,
        Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getType(),
                account.getBalance(),
                account.getCustomerId(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }
}
