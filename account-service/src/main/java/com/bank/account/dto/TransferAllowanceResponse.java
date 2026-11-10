package com.bank.account.dto;

import java.math.BigDecimal;

/** How much the account's owner may still send by transfer today. */
public record TransferAllowanceResponse(BigDecimal dailyLimit, BigDecimal sentToday, BigDecimal remainingToday) {
}
