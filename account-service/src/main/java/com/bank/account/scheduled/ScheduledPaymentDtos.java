package com.bank.account.scheduled;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** What the scheduled payments API takes and gives back. */
public final class ScheduledPaymentDtos {

    private ScheduledPaymentDtos() {
    }

    public record SetUp(
            @NotNull Long fromAccountId,
            @NotNull @Pattern(regexp = "\\d{12}", message = "must be a 12-digit account number") String toAccountNumber,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Min(1) @Max(ScheduledPayment.LAST_DAY) Integer dayOfMonth,
            @Size(max = 60) String reference) {
    }

    public record ScheduledPaymentResponse(
            Long id,
            Long customerId,
            Long fromAccountId,
            String fromAccountNumber,
            String toAccountNumber,
            BigDecimal amount,
            int dayOfMonth,
            String reference,
            ScheduledPayment.Status status,
            LocalDate nextPaymentOn,
            LocalDate lastSentOn,
            String problem,
            Instant createdAt) {

        static ScheduledPaymentResponse from(ScheduledPayment payment, String fromAccountNumber) {
            return new ScheduledPaymentResponse(payment.getId(), payment.getCustomerId(), payment.getFromAccountId(),
                    fromAccountNumber, payment.getToAccountNumber(), payment.getAmount(), payment.getDayOfMonth(),
                    payment.getReference(), payment.getStatus(), payment.getNextPaymentOn(), payment.getLastSentOn(),
                    payment.getProblem(), payment.getCreatedAt());
        }
    }
}
