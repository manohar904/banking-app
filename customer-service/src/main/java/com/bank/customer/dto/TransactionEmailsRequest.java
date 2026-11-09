package com.bank.customer.dto;

import jakarta.validation.constraints.NotNull;

public record TransactionEmailsRequest(@NotNull Boolean enabled) {
}
