package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.account.savings.SavingsInterest.Outcome;
import com.bank.account.savings.SavingsInterestRunner;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Interest on savings accounts, against real PostgreSQL. Requires Docker. */
@SpringBootTest(properties = {
        "bank.kafka.enabled=false",
        "bank.savings.annual-interest-rate=3.00",
        // Run by the tests themselves, not by the clock.
        "bank.savings.cron=-",
        "bank.scheduled-payments.cron=-"
})
@AutoConfigureMockMvc
@Testcontainers
class SavingsInterestIntegrationTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    static TestGateway gateway = TestGateway.start();

    @DynamicPropertySource
    static void trustTheTestGateway(DynamicPropertyRegistry registry) {
        gateway.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SavingsInterestRunner runner;

    @MockitoBean
    private CustomerServiceClient customerService;

    private static final YearMonth LAST_MONTH = YearMonth.now(ZoneOffset.UTC).minusMonths(1);
    private static final LocalDate THIS_MONTH = YearMonth.now(ZoneOffset.UTC).atDay(1);

    @Test
    void aNewSavingsAccountIsGivenItsFirstPaymentDayAndACheckingAccountNone() throws Exception {
        long customer = newCustomerId();
        long savings = createAccount(customer, "SAVINGS");
        long checking = createAccount(customer, "CHECKING");

        runner.run();
        assertThat(nextInterestOn(savings)).isEqualTo(THIS_MONTH.plusMonths(1));
        assertThat(nextInterestOn(checking)).isNull();

        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}", savings)))
                .andExpect(jsonPath("$.annualInterestRate").value(3.00))
                .andExpect(jsonPath("$.nextInterestOn").value(THIS_MONTH.plusMonths(1).toString()));
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}", checking)))
                .andExpect(jsonPath("$.annualInterestRate").doesNotExist())
                .andExpect(jsonPath("$.nextInterestOn").doesNotExist());
    }

    @Test
    void aWholeMonthAtOneBalanceEarnsTheRateForThoseDaysAndIsPaidOnce() throws Exception {
        long customer = newCustomerId();
        long savings = createAccount(customer, "SAVINGS");
        deposit(savings, "1000.00");
        openedOn(savings, LAST_MONTH.atDay(1));
        dateEntries(savings, LAST_MONTH.atDay(1));
        dueOn(savings, THIS_MONTH);

        assertThat(runner.run()).containsEntry(Outcome.PAID, 1);
        // 1000 x 3% x days / 365, part-cents dropped: 2.46 for 30 days, 2.54 for 31.
        BigDecimal expected = new BigDecimal("1000.00").multiply(BigDecimal.valueOf(LAST_MONTH.lengthOfMonth()))
                .multiply(new BigDecimal("3.00")).divide(new BigDecimal("36500"), 2, java.math.RoundingMode.DOWN);
        assertThat(balance(savings)).isEqualByComparingTo(new BigDecimal("1000.00").add(expected));
        assertThat(jdbc.queryForObject("select amount from account_transactions where account_id = ? and type = 'INTEREST'",
                BigDecimal.class, savings)).isEqualByComparingTo(expected);
        assertThat(nextInterestOn(savings)).isEqualTo(THIS_MONTH.plusMonths(1));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload like ?",
                Integer.class, String.valueOf(savings), "%\"INTEREST_PAID\"%")).isEqualTo(1);

        runner.run();
        assertThat(jdbc.queryForObject("select count(*) from account_transactions where account_id = ? and type = 'INTEREST'",
                Integer.class, savings)).as("paid once").isEqualTo(1);
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/transactions", savings)))
                .andExpect(jsonPath("$.content[0].type").value("INTEREST"));
    }

    @Test
    void onlyTheDaysTheMoneyWasThereCount() throws Exception {
        long customer = newCustomerId();
        long savings = createAccount(customer, "SAVINGS");
        // Opened on the 10th with nothing in it; 3,650.00 arrives on the 21st and stays to the month's end.
        deposit(savings, "3650.00");
        openedOn(savings, LAST_MONTH.atDay(10));
        dateEntries(savings, LAST_MONTH.atDay(21));
        dueOn(savings, THIS_MONTH);

        runner.run();
        int days = LAST_MONTH.lengthOfMonth() - 20;
        // 3,650 x 3% / 365 is exactly 0.30 a day.
        BigDecimal expected = new BigDecimal("0.30").multiply(BigDecimal.valueOf(days));
        assertThat(balance(savings)).isEqualByComparingTo(new BigDecimal("3650.00").add(expected));
    }

    @Test
    void anEmptyAccountEarnsNothingAndMovesOnToNextMonth() throws Exception {
        long savings = createAccount(newCustomerId(), "SAVINGS");
        openedOn(savings, LAST_MONTH.atDay(1));
        dueOn(savings, THIS_MONTH);

        assertThat(runner.run()).containsEntry(Outcome.NOTHING_EARNED, 1);
        assertThat(balance(savings)).isEqualByComparingTo("0.00");
        assertThat(nextInterestOn(savings)).isEqualTo(THIS_MONTH.plusMonths(1));
    }

    private void openedOn(long account, LocalDate day) {
        jdbc.update("update accounts set created_at = ? where id = ?", noonOf(day), account);
    }

    private void dateEntries(long account, LocalDate day) {
        jdbc.update("update account_transactions set created_at = ? where account_id = ?", noonOf(day), account);
    }

    private void dueOn(long account, LocalDate day) {
        jdbc.update("update accounts set next_interest_on = ? where id = ?", day, account);
    }

    private static Timestamp noonOf(LocalDate day) {
        return Timestamp.from(day.atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    private LocalDate nextInterestOn(long account) {
        return jdbc.queryForObject("select next_interest_on from accounts where id = ?", LocalDate.class, account);
    }

    private long createAccount(long customer, String type) throws Exception {
        return idOf(mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void deposit(long account, String amount) throws Exception {
        mockMvc.perform(asStaff(post("/api/accounts/{id}/deposit", account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}")).andExpect(status().isOk());
    }

    private BigDecimal balance(long account) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, account);
    }

    private static long idOf(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static long newCustomerId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return as(gateway.forUser(1L, "STAFF", null), request);
    }

    private MockHttpServletRequestBuilder asCustomer(long customerId, MockHttpServletRequestBuilder request) {
        return as(gateway.forUser(2L, "CUSTOMER", customerId), request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
