package com.bank.account.savings;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.account.savings.SavingsGoals.GoalResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SavingsGoalProgressTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    private static GoalResponse progress(String target, LocalDate by, String balance) {
        return SavingsGoals.progress(1L, "Holiday", new BigDecimal(target), by, new BigDecimal(balance), TODAY);
    }

    @Test
    void partWayThereShowsWhatIsLeftAndAWholePercentRoundedDown() {
        GoalResponse goal = progress("2000.00", null, "1250.00");
        assertThat(goal.remaining()).isEqualByComparingTo("750.00");
        assertThat(goal.percent()).isEqualTo(62);
        assertThat(goal.reached()).isFalse();
        assertThat(goal.perMonth()).isNull();

        assertThat(progress("100.00", null, "99.99").percent()).isEqualTo(99);
        assertThat(progress("100.00", null, "0.00").percent()).isZero();
    }

    @Test
    void reachingOrPassingTheAmountIsReachedAtAHundredPercent() {
        GoalResponse exactly = progress("500.00", TODAY.plusMonths(3), "500.00");
        assertThat(exactly.reached()).isTrue();
        assertThat(exactly.percent()).isEqualTo(100);
        assertThat(exactly.remaining()).isEqualByComparingTo("0.00");
        assertThat(exactly.perMonth()).isNull();

        GoalResponse beyond = progress("500.00", null, "820.40");
        assertThat(beyond.percent()).isEqualTo(100);
        assertThat(beyond.remaining()).isEqualByComparingTo("0.00");
    }

    @Test
    void withADateItSaysWhatToSaveEachMonthCountingThisOneAndRoundedUp() {
        // November to January is three months: 1000 / 3 = 333.333..., so 333.34 a month.
        GoalResponse goal = progress("1000.00", LocalDate.of(2027, 1, 15), "0.00");
        assertThat(goal.perMonth()).isEqualByComparingTo("333.34");

        // A date later this month leaves all of it for this month.
        assertThat(progress("1000.00", TODAY.plusDays(5), "400.00").perMonth()).isEqualByComparingTo("600.00");
        assertThat(progress("1000.00", TODAY, "400.00").pastTargetDate()).isFalse();
    }

    @Test
    void aDateThatHasPassedIsSaidSoWithNoMonthlyAmount() {
        GoalResponse goal = progress("1000.00", TODAY.minusDays(1), "400.00");
        assertThat(goal.pastTargetDate()).isTrue();
        assertThat(goal.perMonth()).isNull();
    }
}
