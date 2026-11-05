package com.bank.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account(42L, "000000000001", AccountType.CHECKING);
    }

    @Test
    void newAccountStartsAtZeroWithTwoDecimals() {
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));
        assertThat(account.hasZeroBalance()).isTrue();
    }

    @Test
    void decimalArithmeticIsExact() {
        account.deposit(new BigDecimal("0.10"));
        account.deposit(new BigDecimal("0.20"));

        // 0.1 + 0.2 would give 0.30000000000000004 as a double.
        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.30"));
    }

    @Test
    void amountsAreNormalizedToTwoDecimals() {
        account.deposit(new BigDecimal("10.5"));
        account.deposit(new BigDecimal("1.50000"));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("12.00"));
    }

    @Test
    void withdrawingTheFullBalanceIsAllowed() {
        account.deposit(new BigDecimal("100.00"));

        account.withdraw(new BigDecimal("100"));

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void overdraftIsRejectedAndLeavesBalanceUnchanged() {
        account.deposit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.withdraw(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.withdraw(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountsAreNeverSilentlyRounded() {
        assertThatThrownBy(() -> account.deposit(new BigDecimal("1.005")))
                .isInstanceOf(ArithmeticException.class);
    }
}
