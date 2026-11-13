package com.bank.account.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One month's statement for a card. Never changed once written. */
@Entity
@Table(name = "card_statements")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class CardStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long cardId;

    @Column(nullable = false, updatable = false)
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal interest;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal minimumPayment;

    @Column(nullable = false, updatable = false)
    private LocalDate dueOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    CardStatement(Long cardId, LocalDate periodStart, LocalDate periodEnd, BigDecimal interest, BigDecimal balance,
            BigDecimal minimumPayment, LocalDate dueOn, Instant createdAt) {
        this.cardId = cardId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.interest = interest;
        this.balance = balance;
        this.minimumPayment = minimumPayment;
        this.dueOn = dueOn;
        this.createdAt = createdAt;
    }
}
