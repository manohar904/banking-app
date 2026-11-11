package com.bank.account.loan;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the loans API takes and gives back. */
public final class LoanDtos {

    private LoanDtos() {
    }

    public record LoanApplication(
            @NotNull Long accountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotNull @Positive Integer months,
            @NotBlank @Size(max = 200) String purpose) {
    }

    public record LoanRejection(@Size(max = 300) String note) {
    }

    /** What a loan would cost, before asking for it. */
    public record LoanQuote(
            BigDecimal amount,
            int months,
            BigDecimal annualRate,
            BigDecimal monthlyPayment,
            BigDecimal totalRepayable,
            BigDecimal totalInterest) {
    }

    /** The limits, so the page can show them. */
    public record LoanOffer(BigDecimal annualRate, BigDecimal minAmount, BigDecimal maxAmount, int minMonths,
            int maxMonths) {
    }

    /** repayments is only filled in when one loan is read; lists leave it empty. */
    public record LoanResponse(
            Long id,
            Long customerId,
            Long accountId,
            String accountNumber,
            LoanStatus status,
            BigDecimal principal,
            BigDecimal annualRate,
            int termMonths,
            BigDecimal monthlyPayment,
            BigDecimal remainingPrincipal,
            int paymentsMade,
            String purpose,
            String decisionNote,
            LocalDate nextPaymentDue,
            BigDecimal nextPaymentAmount,
            boolean overdue,
            Instant requestedAt,
            Instant decidedAt,
            Instant closedAt,
            List<RepaymentResponse> repayments) {

        static LoanResponse from(Loan loan, String accountNumber, LocalDate today, List<LoanRepayment> repayments) {
            boolean active = loan.getStatus() == LoanStatus.ACTIVE;
            return new LoanResponse(loan.getId(), loan.getCustomerId(), loan.getAccountId(), accountNumber,
                    loan.getStatus(), loan.getPrincipal(), loan.getAnnualRate(), loan.getTermMonths(),
                    loan.getMonthlyPayment(), loan.getRemainingPrincipal(), loan.getPaymentsMade(), loan.getPurpose(),
                    loan.getDecisionNote(), loan.getNextPaymentDue(),
                    active ? loan.nextRepayment(false).amount() : null,
                    active && today.isAfter(loan.getNextPaymentDue()),
                    loan.getRequestedAt(), loan.getDecidedAt(), loan.getClosedAt(),
                    repayments == null ? null : repayments.stream().map(RepaymentResponse::from).toList());
        }
    }

    public record RepaymentResponse(Long id, BigDecimal amount, BigDecimal interest, BigDecimal principal,
            BigDecimal remainingAfter, Instant createdAt) {

        static RepaymentResponse from(LoanRepayment repayment) {
            return new RepaymentResponse(repayment.getId(), repayment.getAmount(), repayment.getInterest(),
                    repayment.getPrincipal(), repayment.getRemainingAfter(), repayment.getCreatedAt());
        }
    }
}
