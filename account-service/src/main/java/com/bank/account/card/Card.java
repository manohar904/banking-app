package com.bank.account.card;

import com.bank.common.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The customer lives in the customer service, so this is an id rather than a relation. */
    @Column(nullable = false, updatable = false)
    private Long customerId;

    /** The only part of the number the bank keeps. */
    @Column(nullable = false, updatable = false, length = 4)
    private String lastFour;

    @Column(nullable = false, updatable = false)
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal creditLimit;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal owed;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    /** Optimistic lock, as on accounts. Cards are also locked while being changed; see CardRepository. */
    @Version
    private Long version;

    Card(Long customerId, String lastFour, LocalDate expiresOn, BigDecimal creditLimit, Instant now) {
        this.customerId = customerId;
        this.lastFour = lastFour;
        this.expiresOn = expiresOn;
        this.status = CardStatus.ACTIVE;
        this.creditLimit = creditLimit;
        this.owed = BigDecimal.ZERO.setScale(2);
        this.createdAt = now;
    }

    public BigDecimal available() {
        return creditLimit.subtract(owed).max(BigDecimal.ZERO);
    }

    /** Refused unless the card is active, in date and has enough credit left. */
    void purchase(BigDecimal amount, LocalDate today) {
        if (status == CardStatus.FROZEN) {
            throw new ConflictException("This card is frozen. Unfreeze it to use it.");
        }
        requireOpen();
        if (today.isAfter(expiresOn)) {
            throw new ConflictException("This card has expired.");
        }
        if (amount.compareTo(available()) > 0) {
            throw new CardLimitExceededException("This is more than the card's available credit of "
                    + available().toPlainString() + ".");
        }
        owed = owed.add(amount);
    }

    /** A frozen card can still be paid off; only a closed one can't. */
    void payment(BigDecimal amount) {
        requireOpen();
        if (owed.signum() == 0) {
            throw new ConflictException("Nothing is owed on this card.");
        }
        if (amount.compareTo(owed) > 0) {
            throw new CardLimitExceededException("This is more than is owed on the card ("
                    + owed.toPlainString() + ").");
        }
        owed = owed.subtract(amount);
    }

    void freeze() {
        requireOpen();
        status = CardStatus.FROZEN;
    }

    void unfreeze() {
        requireOpen();
        status = CardStatus.ACTIVE;
    }

    /** Lowering the limit below what is already owed would leave the card over its limit. */
    void changeLimit(BigDecimal limit) {
        requireOpen();
        if (limit.compareTo(owed) < 0) {
            throw new ConflictException("The limit can't be lower than what is owed (" + owed.toPlainString() + ").");
        }
        creditLimit = limit;
    }

    void close(Instant now) {
        requireOpen();
        if (owed.signum() != 0) {
            throw new ConflictException("A card can only be closed when nothing is owed on it.");
        }
        status = CardStatus.CLOSED;
        closedAt = now;
    }

    private void requireOpen() {
        if (status == CardStatus.CLOSED) {
            throw new ConflictException("This card is closed.");
        }
    }
}
