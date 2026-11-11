package com.bank.account.loan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the bank lends on, and the arithmetic. A loan is repaid in equal monthly payments; each one
 * first pays the month's interest on what is still owed, and the rest pays the loan down. The last
 * payment is whatever is left, so rounding never leaves a few cents owing.
 */
@Component
public class LoanTerms {

    private static final MathContext PRECISE = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = new BigDecimal("1200");

    private final BigDecimal annualRate;
    private final BigDecimal minAmount;
    private final BigDecimal maxAmount;
    private final int minMonths;
    private final int maxMonths;

    LoanTerms(@Value("${bank.loans.annual-interest-rate}") BigDecimal annualRate,
            @Value("${bank.loans.min-amount}") BigDecimal minAmount,
            @Value("${bank.loans.max-amount}") BigDecimal maxAmount,
            @Value("${bank.loans.min-months}") int minMonths,
            @Value("${bank.loans.max-months}") int maxMonths) {
        this.annualRate = annualRate.setScale(2, RoundingMode.UNNECESSARY);
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.minMonths = minMonths;
        this.maxMonths = maxMonths;
    }

    public BigDecimal annualRate() {
        return annualRate;
    }

    public BigDecimal minAmount() {
        return minAmount;
    }

    public BigDecimal maxAmount() {
        return maxAmount;
    }

    public int minMonths() {
        return minMonths;
    }

    public int maxMonths() {
        return maxMonths;
    }

    /** Null when the request is within limits; otherwise what is wrong, for the customer to read. */
    public String problemWith(BigDecimal amount, int months) {
        if (amount.compareTo(minAmount) < 0 || amount.compareTo(maxAmount) > 0) {
            return "You can borrow from " + minAmount.toPlainString() + " to " + maxAmount.toPlainString() + ".";
        }
        if (months < minMonths || months > maxMonths) {
            return "A loan can last from " + minMonths + " to " + maxMonths + " months.";
        }
        return null;
    }

    /** The standard annuity payment, P × r / (1 − (1 + r)^−n), rounded up to whole cents. */
    public static BigDecimal monthlyPayment(BigDecimal principal, BigDecimal annualRate, int months) {
        if (annualRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(months), 2, RoundingMode.CEILING);
        }
        BigDecimal rate = monthlyRate(annualRate);
        BigDecimal growth = BigDecimal.ONE.add(rate).pow(months, PRECISE);
        return principal.multiply(rate).multiply(growth)
                .divide(growth.subtract(BigDecimal.ONE), PRECISE)
                .setScale(2, RoundingMode.CEILING);
    }

    /** One month's interest on what is still owed, to the nearest cent. */
    public static BigDecimal monthInterest(BigDecimal remaining, BigDecimal annualRate) {
        return remaining.multiply(monthlyRate(annualRate)).setScale(2, RoundingMode.HALF_UP);
    }

    /** What the whole loan costs if every payment is made on schedule. */
    public static BigDecimal totalRepayable(BigDecimal principal, BigDecimal annualRate, int months) {
        BigDecimal payment = monthlyPayment(principal, annualRate, months);
        BigDecimal remaining = principal;
        BigDecimal total = BigDecimal.ZERO;
        for (int month = 1; month <= months && remaining.signum() > 0; month++) {
            Repayment next = nextRepayment(remaining, annualRate, payment, month == months);
            total = total.add(next.amount());
            remaining = next.remainingAfter();
        }
        return total;
    }

    /** The next monthly repayment. The last one, or one that would overpay, clears the loan exactly. */
    public static Repayment nextRepayment(BigDecimal remaining, BigDecimal annualRate, BigDecimal monthlyPayment,
            boolean last) {
        BigDecimal interest = monthInterest(remaining, annualRate);
        BigDecimal toClear = remaining.add(interest);
        if (last || toClear.compareTo(monthlyPayment) <= 0) {
            return new Repayment(toClear, interest, remaining, BigDecimal.ZERO.setScale(2));
        }
        BigDecimal principalPart = monthlyPayment.subtract(interest);
        return new Repayment(monthlyPayment, interest, principalPart, remaining.subtract(principalPart));
    }

    private static BigDecimal monthlyRate(BigDecimal annualRate) {
        return annualRate.divide(TWELVE_HUNDRED, PRECISE);
    }

    public record Repayment(BigDecimal amount, BigDecimal interest, BigDecimal principal, BigDecimal remainingAfter) {
    }
}
