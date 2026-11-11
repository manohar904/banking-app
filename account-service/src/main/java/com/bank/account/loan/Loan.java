package com.bank.account.loan;

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
@Table(name = "loans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    /** Paid into on approval, and repaid from. */
    @Column(nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LoanStatus status;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(nullable = false, updatable = false)
    private int termMonths;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal remainingPrincipal;

    @Column(nullable = false)
    private int paymentsMade;

    @Column(nullable = false, updatable = false, length = 200)
    private String purpose;

    @Column(length = 300)
    private String decisionNote;

    private LocalDate nextPaymentDue;

    @Column(nullable = false, updatable = false)
    private Instant requestedAt;

    private Instant decidedAt;

    private Instant closedAt;

    /** Optimistic lock, as on accounts. Loans are also locked while being changed; see LoanRepository. */
    @Version
    private Long version;

    Loan(Long customerId, Long accountId, BigDecimal principal, BigDecimal annualRate, int termMonths,
            String purpose, Instant now) {
        this.customerId = customerId;
        this.accountId = accountId;
        this.status = LoanStatus.REQUESTED;
        this.principal = principal;
        this.annualRate = annualRate;
        this.termMonths = termMonths;
        this.monthlyPayment = LoanTerms.monthlyPayment(principal, annualRate, termMonths);
        this.remainingPrincipal = principal;
        this.purpose = purpose;
        this.requestedAt = now;
    }

    void approve(Instant now, LocalDate today) {
        requireStatus(LoanStatus.REQUESTED, "Only a loan that is waiting can be approved.");
        status = LoanStatus.ACTIVE;
        decidedAt = now;
        nextPaymentDue = today.plusMonths(1);
    }

    void reject(String note, Instant now) {
        requireStatus(LoanStatus.REQUESTED, "Only a loan that is waiting can be rejected.");
        status = LoanStatus.REJECTED;
        decisionNote = note;
        decidedAt = now;
        closedAt = now;
    }

    void cancel(Instant now) {
        requireStatus(LoanStatus.REQUESTED, "Only a loan that is waiting can be cancelled.");
        status = LoanStatus.CANCELLED;
        closedAt = now;
    }

    /** The repayment due now: the regular one, or with payOff everything that is left. */
    LoanTerms.Repayment nextRepayment(boolean payOff) {
        requireStatus(LoanStatus.ACTIVE, "Only an active loan can be repaid.");
        if (payOff) {
            // Interest is charged a month at a time, so paying off early costs only what is owed.
            return new LoanTerms.Repayment(remainingPrincipal, BigDecimal.ZERO.setScale(2), remainingPrincipal,
                    BigDecimal.ZERO.setScale(2));
        }
        return LoanTerms.nextRepayment(remainingPrincipal, annualRate, monthlyPayment, paymentsMade + 1 >= termMonths);
    }

    void repaid(LoanTerms.Repayment repayment, Instant now) {
        remainingPrincipal = repayment.remainingAfter();
        paymentsMade++;
        if (remainingPrincipal.signum() == 0) {
            status = LoanStatus.PAID_OFF;
            nextPaymentDue = null;
            closedAt = now;
        } else {
            nextPaymentDue = nextPaymentDue.plusMonths(1);
        }
    }

    private void requireStatus(LoanStatus expected, String message) {
        if (status != expected) {
            throw new ConflictException(message);
        }
    }
}
