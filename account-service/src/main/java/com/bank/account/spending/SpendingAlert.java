package com.bank.account.spending;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** How much money out in a month an account's owner wants to be told about. One per account. */
@Entity
@Table(name = "spending_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class SpendingAlert {

    @Id
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** The first day of the month the email was last sent for, or null if not since the amount was set. */
    private LocalDate alertedFor;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    SpendingAlert(Long accountId, Instant now) {
        this.accountId = accountId;
        this.createdAt = now;
    }

    /** A new amount starts afresh: going over it this month sends an email, even if the old one did. */
    void set(BigDecimal amount, Instant now) {
        this.amount = amount;
        this.alertedFor = null;
        this.updatedAt = now;
    }

    void alerted(LocalDate month) {
        this.alertedFor = month;
    }
}
