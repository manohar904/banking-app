package com.bank.account.dto;

import com.bank.account.Account;
import com.bank.account.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * nextInterestOn is a savings account's next interest payment (empty until the interest job first sees
 * a new account). annualInterestRate is only filled in when one account is read.
 */
public record AccountResponse(
        Long id,
        String accountNumber,
        AccountType type,
        BigDecimal balance,
        Long customerId,
        Instant createdAt,
        Instant updatedAt,
        LocalDate nextInterestOn,
        BigDecimal annualInterestRate) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getType(),
                account.getBalance(),
                account.getCustomerId(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getNextInterestOn(),
                null);
    }

    public AccountResponse withAnnualInterestRate(BigDecimal rate) {
        return new AccountResponse(id, accountNumber, type, balance, customerId, createdAt, updatedAt, nextInterestOn,
                rate);
    }
}
