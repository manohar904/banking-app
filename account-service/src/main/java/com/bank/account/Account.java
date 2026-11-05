package com.bank.account;

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
import java.math.RoundingMode;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class Account {

    private static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 12)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    @Column(nullable = false, precision = 19, scale = MONEY_SCALE)
    private BigDecimal balance;

    /** The customer lives in the customer service, so this is an id rather than a relation. */
    @Column(nullable = false, updatable = false)
    private Long customerId;

    /** Optimistic lock: stops concurrent updates from silently overwriting each other. */
    @Version
    private Long version;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    public Account(Long customerId, String accountNumber, AccountType type) {
        this.customerId = customerId;
        this.accountNumber = accountNumber;
        this.type = type;
        this.balance = BigDecimal.ZERO.setScale(MONEY_SCALE);
    }

    public void deposit(BigDecimal amount) {
        balance = balance.add(validated(amount));
    }

    public void withdraw(BigDecimal amount) {
        BigDecimal validAmount = validated(amount);
        // compareTo, not equals: BigDecimal.equals treats 1.0 and 1.00 as different values.
        if (balance.compareTo(validAmount) < 0) {
            throw new InsufficientFundsException("Insufficient funds in account " + accountNumber + ".");
        }
        balance = balance.subtract(validAmount);
    }

    public boolean hasZeroBalance() {
        return balance.signum() == 0;
    }

    /** Brings the amount to two decimals. Throws rather than rounding away part of a sum. */
    private static BigDecimal validated(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        return amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }
}
