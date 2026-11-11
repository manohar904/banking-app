package com.bank.account.card;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** What the cards API takes and gives back. Amounts have the same limits as the ledger's columns. */
public final class CardDtos {

    private CardDtos() {
    }

    public record IssueCard(
            @NotNull Long customerId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit) {
    }

    public record NewLimit(@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal creditLimit) {
    }

    public record Purchase(
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 100) String merchant) {
    }

    public record Payment(
            @NotNull Long fromAccountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount) {
    }

    public record CardResponse(
            Long id,
            Long customerId,
            String lastFour,
            LocalDate expiresOn,
            CardStatus status,
            BigDecimal creditLimit,
            BigDecimal owed,
            BigDecimal available,
            Instant createdAt,
            Instant closedAt) {

        static CardResponse from(Card card) {
            return new CardResponse(card.getId(), card.getCustomerId(), card.getLastFour(), card.getExpiresOn(),
                    card.getStatus(), card.getCreditLimit(), card.getOwed(), card.available(), card.getCreatedAt(),
                    card.getClosedAt());
        }
    }

    /** accountNumber is the account a payment came from; merchant where a purchase was made. */
    public record CardTransactionResponse(
            Long id,
            CardTransaction.Type type,
            BigDecimal amount,
            BigDecimal owedAfter,
            String merchant,
            String accountNumber,
            Instant createdAt) {
    }
}
