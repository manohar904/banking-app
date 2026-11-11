package com.bank.account.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** One repayment, split into interest and the part that paid the loan down. Never changed once written. */
@Entity
@Table(name = "loan_repayments")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class LoanRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long loanId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal interest;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal remainingAfter;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    LoanRepayment(Long loanId, LoanTerms.Repayment repayment, Instant now) {
        this.loanId = loanId;
        this.amount = repayment.amount();
        this.interest = repayment.interest();
        this.principal = repayment.principal();
        this.remainingAfter = repayment.remainingAfter();
        this.createdAt = now;
    }
}
