package com.bank.account.savings;

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

/** What a savings account is being saved towards. One per account, keyed by the account's id. */
@Entity
@Table(name = "savings_goals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class SavingsGoal {

    @Id
    private Long accountId;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    private LocalDate targetDate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    SavingsGoal(Long accountId, Instant now) {
        this.accountId = accountId;
        this.createdAt = now;
    }

    void set(String name, BigDecimal targetAmount, LocalDate targetDate, Instant now) {
        this.name = name;
        this.targetAmount = targetAmount;
        this.targetDate = targetDate;
        this.updatedAt = now;
    }
}
