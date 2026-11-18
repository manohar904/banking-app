package com.bank.account.scheduled;

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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A transfer sent every month on the same day until it is cancelled. */
@Entity
@Table(name = "scheduled_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA, not for application code
public class ScheduledPayment {

    public enum Status {
        ACTIVE,
        CANCELLED
    }

    /** The last day of the month a payment can be set for: every month has it. */
    static final int LAST_DAY = 28;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false)
    private Long fromAccountId;

    @Column(nullable = false, updatable = false, length = 12)
    private String toAccountNumber;

    @Column(nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private int dayOfMonth;

    @Column(updatable = false, length = 60)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDate nextPaymentOn;

    private LocalDate lastSentOn;

    @Column(length = 200)
    private String problem;

    private LocalDate problemFor;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant cancelledAt;

    ScheduledPayment(Long customerId, Long fromAccountId, String toAccountNumber, BigDecimal amount, int dayOfMonth,
            String reference, LocalDate today, Instant now) {
        this.customerId = customerId;
        this.fromAccountId = fromAccountId;
        this.toAccountNumber = toAccountNumber;
        this.amount = amount;
        this.dayOfMonth = dayOfMonth;
        this.reference = reference;
        this.status = Status.ACTIVE;
        this.nextPaymentOn = nextOnOrAfter(today);
        this.createdAt = now;
    }

    boolean isDue(LocalDate today) {
        return status == Status.ACTIVE && !nextPaymentOn.isAfter(today);
    }

    /** Sent: the next one is the first payment day after today, so days missed while the bank was down aren't sent twice. */
    void sent(LocalDate today) {
        lastSentOn = today;
        problem = null;
        problemFor = null;
        nextPaymentOn = nextOnOrAfter(today.plusDays(1));
    }

    /**
     * Couldn't be sent. Returns true the first time for this payment day, when the customer should be
     * told. After giveUpAfterDays the month is skipped and the next try is next month's day.
     */
    boolean failed(String why, LocalDate today, int giveUpAfterDays) {
        boolean firstTime = !nextPaymentOn.equals(problemFor);
        problemFor = nextPaymentOn;
        if (today.isBefore(nextPaymentOn.plusDays(giveUpAfterDays))) {
            problem = why + " Trying again.";
        } else {
            problem = why + " The payment due " + nextPaymentOn + " was skipped.";
            nextPaymentOn = nextOnOrAfter(today.plusDays(1));
        }
        return firstTime;
    }

    void cancel(Instant now) {
        status = Status.CANCELLED;
        cancelledAt = now;
    }

    /** This payment's day in the month of the given date, or in the month after if that day has passed. */
    private LocalDate nextOnOrAfter(LocalDate date) {
        LocalDate thisMonth = date.withDayOfMonth(dayOfMonth);
        return thisMonth.isBefore(date) ? thisMonth.plusMonths(1) : thisMonth;
    }
}
