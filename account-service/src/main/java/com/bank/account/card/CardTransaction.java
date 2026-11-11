package com.bank.account.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One purchase or payment. Never changed once written. */
@Entity
@Table(name = "card_transactions")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class CardTransaction {

    public enum Type {
        PURCHASE,
        PAYMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private Type type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal owedAfter;

    @Column(updatable = false, length = 100)
    private String merchant;

    @Column(updatable = false)
    private Long accountId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    static CardTransaction purchase(Card card, BigDecimal amount, String merchant, Instant now) {
        CardTransaction entry = new CardTransaction(card, Type.PURCHASE, amount, now);
        entry.merchant = merchant;
        return entry;
    }

    static CardTransaction payment(Card card, BigDecimal amount, Long accountId, Instant now) {
        CardTransaction entry = new CardTransaction(card, Type.PAYMENT, amount, now);
        entry.accountId = accountId;
        return entry;
    }

    /** Call after the card has changed, so that owedAfter captures the new amount owed. */
    private CardTransaction(Card card, Type type, BigDecimal amount, Instant now) {
        this.cardId = card.getId();
        this.type = type;
        this.amount = amount;
        this.owedAfter = card.getOwed();
        this.createdAt = now;
    }
}
