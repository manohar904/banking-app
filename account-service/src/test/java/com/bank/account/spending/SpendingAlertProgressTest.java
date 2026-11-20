package com.bank.account.spending;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.account.spending.SpendingAlerts.AlertResponse;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SpendingAlertProgressTest {

    private static AlertResponse progress(String amount, String spent) {
        return SpendingAlerts.progress(1L, new BigDecimal(amount), new BigDecimal(spent));
    }

    @Test
    void underTheAmountShowsWhatIsLeftAndAWholePercentRoundedDown() {
        AlertResponse alert = progress("1000.00", "625.50");
        assertThat(alert.remaining()).isEqualByComparingTo("374.50");
        assertThat(alert.percent()).isEqualTo(62);
        assertThat(alert.passed()).isFalse();

        assertThat(progress("100.00", "99.99").percent()).isEqualTo(99);
        assertThat(progress("100.00", "0.00").percent()).isZero();
    }

    @Test
    void exactlyTheAmountIsNotYetOverIt() {
        AlertResponse alert = progress("100.00", "100.00");
        assertThat(alert.passed()).isFalse();
        assertThat(alert.percent()).isEqualTo(100);
        assertThat(alert.remaining()).isEqualByComparingTo("0.00");
    }

    @Test
    void overTheAmountStopsAtAHundredPercentAndNothingLeft() {
        AlertResponse alert = progress("100.00", "250.00");
        assertThat(alert.passed()).isTrue();
        assertThat(alert.percent()).isEqualTo(100);
        assertThat(alert.remaining()).isEqualByComparingTo("0.00");
        assertThat(alert.spentThisMonth()).isEqualByComparingTo("250.00");
    }
}
