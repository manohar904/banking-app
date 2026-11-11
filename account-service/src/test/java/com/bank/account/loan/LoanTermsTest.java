package com.bank.account.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LoanTermsTest {

    private static final BigDecimal RATE = new BigDecimal("9.50");

    @Test
    void theMonthlyPaymentIsTheStandardAnnuityRoundedUpToACent() {
        // 1000 at 9.5% over 12 months is 87.684..., so 87.69.
        assertThat(LoanTerms.monthlyPayment(new BigDecimal("1000.00"), RATE, 12)).isEqualByComparingTo("87.69");
        assertThat(LoanTerms.monthlyPayment(new BigDecimal("1200.00"), BigDecimal.ZERO, 12)).isEqualByComparingTo("100.00");
    }

    @Test
    void repayingOnScheduleClearsTheLoanExactly() {
        BigDecimal principal = new BigDecimal("12345.67");
        int months = 37;
        BigDecimal payment = LoanTerms.monthlyPayment(principal, RATE, months);
        BigDecimal remaining = principal;
        BigDecimal paidDown = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int made = 0;
        while (remaining.signum() > 0) {
            LoanTerms.Repayment next = LoanTerms.nextRepayment(remaining, RATE, payment, made + 1 >= months);
            assertThat(next.amount()).isEqualByComparingTo(next.interest().add(next.principal()));
            assertThat(next.amount()).isLessThanOrEqualTo(payment.add(new BigDecimal("0.50")));
            paidDown = paidDown.add(next.principal());
            total = total.add(next.amount());
            remaining = next.remainingAfter();
            made++;
        }
        assertThat(made).isEqualTo(months);
        assertThat(paidDown).isEqualByComparingTo(principal);
        assertThat(total).isEqualByComparingTo(LoanTerms.totalRepayable(principal, RATE, months));
    }

    @Test
    void theFirstMonthsInterestIsOneTwelfthOfTheYearlyRate() {
        assertThat(LoanTerms.monthInterest(new BigDecimal("1000.00"), RATE)).isEqualByComparingTo("7.92");
    }
}
