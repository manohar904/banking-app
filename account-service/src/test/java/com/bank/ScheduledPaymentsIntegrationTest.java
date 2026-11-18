package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.account.scheduled.ScheduledPaymentRunner;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Monthly payments, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = {
        "bank.kafka.enabled=false",
        "bank.transfers.customer-daily-limit=100.00",
        // Run by the tests themselves, not by the clock.
        "bank.scheduled-payments.cron=-"
})
@AutoConfigureMockMvc
@Testcontainers
class ScheduledPaymentsIntegrationTest {

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
    private ScheduledPaymentRunner runner;

    @MockitoBean
    private CustomerServiceClient customerService;

    @Test
    void aPaymentIsSentOnItsDayAsTheCustomersOwnTransferAndThenNextMonth() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        long to = createAccount(newCustomerId());
        deposit(from, "200.00");
        int day = LocalDate.now(ZoneOffset.UTC).getDayOfMonth() <= 28 ? LocalDate.now(ZoneOffset.UTC).getDayOfMonth() : 1;

        long payment = idOf(setUp(customer, from, numberOf(to), "75.50", day, "  Rent  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reference").value("Rent"))
                .andExpect(jsonPath("$.fromAccountNumber").value(numberOf(from)))
                .andReturn().getResponse().getContentAsString());
        makeDueToday(payment);

        runner.run();
        assertThat(balance(from)).isEqualByComparingTo("124.50");
        assertThat(balance(to)).isEqualByComparingTo("75.50");
        assertThat(jdbc.queryForObject("select by_customer from account_transactions where account_id = ? "
                + "and type = 'TRANSFER_OUT'", Boolean.class, from)).as("counts towards the daily limit").isTrue();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        mockMvc.perform(asCustomer(customer, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$[0].lastSentOn").value(today.toString()))
                .andExpect(jsonPath("$[0].nextPaymentOn").value(today.plusMonths(1).withDayOfMonth(day).toString()))
                .andExpect(jsonPath("$[0].problem").doesNotExist());

        runner.run();
        assertThat(balance(from)).as("sent once").isEqualByComparingTo("124.50");
    }

    @Test
    void withTooLittleMoneyItIsTriedAgainAndTheCustomerToldOnceThenTheMonthIsSkipped() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        long to = createAccount(newCustomerId());
        deposit(from, "10.00");
        long payment = idOf(setUp(customer, from, numberOf(to), "50.00", 5, null)
                .andReturn().getResponse().getContentAsString());
        makeDueToday(payment);

        runner.run();
        runner.run();
        assertThat(balance(from)).isEqualByComparingTo("10.00");
        assertThat(notSentEvents(from)).as("told once").isEqualTo(1);
        mockMvc.perform(asCustomer(customer, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$[0].problem").value("There wasn't enough money in the account. Trying again."))
                .andExpect(jsonPath("$[0].nextPaymentOn").value(LocalDate.now(ZoneOffset.UTC).toString()));

        // Money arrives: the next run sends it.
        deposit(from, "40.00");
        runner.run();
        assertThat(balance(from)).isEqualByComparingTo("0.00");
        mockMvc.perform(asCustomer(customer, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$[0].problem").doesNotExist());

        // Three days late and still not enough: that month is skipped.
        LocalDate threeDaysAgo = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        jdbc.update("update scheduled_payments set next_payment_on = ? where id = ?", threeDaysAgo, payment);
        runner.run();
        assertThat(notSentEvents(from)).isEqualTo(2);
        LocalDate next = jdbc.queryForObject("select next_payment_on from scheduled_payments where id = ?",
                LocalDate.class, payment);
        assertThat(next).isAfter(LocalDate.now(ZoneOffset.UTC));
        assertThat(next.getDayOfMonth()).isEqualTo(5);
        mockMvc.perform(asCustomer(customer, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$[0].problem").value(org.hamcrest.Matchers.endsWith(
                        "The payment due " + threeDaysAgo + " was skipped.")));
    }

    @Test
    void aPaymentIsHeldToTheDailyTransferLimit() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        long to = createAccount(newCustomerId());
        deposit(from, "500.00");
        long payment = idOf(setUp(customer, from, numberOf(to), "80.00", 1, null)
                .andReturn().getResponse().getContentAsString());
        // 50 of today's 100 already sent.
        mockMvc.perform(asCustomer(customer, post("/api/accounts/{id}/transfer", from))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAccountNumber\": \"" + numberOf(to) + "\", \"amount\": 50.00}"))
                .andExpect(status().isOk());
        makeDueToday(payment);

        runner.run();
        assertThat(balance(from)).isEqualByComparingTo("450.00");
        mockMvc.perform(asCustomer(customer, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$[0].problem").value(org.hamcrest.Matchers.startsWith(
                        "It would have gone over the daily transfer limit.")));
    }

    @Test
    void whatCanBeSetUp() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        String to = numberOf(createAccount(newCustomerId()));

        setUp(customer, from, to, "10.00", 29, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dayOfMonth").exists());
        setUp(customer, from, to, "10.00", 0, null).andExpect(status().isBadRequest());
        setUp(customer, from, "123", "10.00", 1, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.toAccountNumber").exists());
        setUp(customer, from, to, "100.01", 1, null).andExpect(status().isUnprocessableContent());
        setUp(customer, from, numberOf(from), "10.00", 1, null).andExpect(status().isBadRequest());
        setUp(customer, from, "000000000000", "10.00", 1, null).andExpect(status().isNotFound());
        setUp(newCustomerId(), from, to, "10.00", 1, null).andExpect(status().isNotFound()); // someone else's account

        for (int i = 0; i < 10; i++) {
            setUp(customer, from, to, "1.00", 1, null).andExpect(status().isCreated());
        }
        setUp(customer, from, to, "1.00", 1, null).andExpect(status().isConflict());
    }

    @Test
    void customersKeepToTheirOwnAndStaffCanHelp() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        String to = numberOf(createAccount(newCustomerId()));
        long payment = idOf(mockMvc.perform(asStaff(post("/api/scheduled-payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "10.00", 3, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customer))
                .andReturn().getResponse().getContentAsString());

        long stranger = newCustomerId();
        mockMvc.perform(asCustomer(stranger, get("/api/scheduled-payments")))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(asCustomer(stranger, get("/api/scheduled-payments")).param("customerId", String.valueOf(customer)))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(asCustomer(stranger, post("/api/scheduled-payments/{id}/cancel", payment)))
                .andExpect(status().isNotFound());
        mockMvc.perform(asStaff(get("/api/scheduled-payments")).param("customerId", String.valueOf(customer)))
                .andExpect(jsonPath("$[0].id").value(payment));
        mockMvc.perform(post("/api/scheduled-payments")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAccountWithAnActivePaymentCannotBeDeletedUntilItIsCancelled() throws Exception {
        long customer = newCustomerId();
        long from = createAccount(customer);
        String to = numberOf(createAccount(newCustomerId()));
        long payment = idOf(setUp(customer, from, to, "10.00", 3, null).andReturn().getResponse().getContentAsString());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", from))).andExpect(status().isConflict());
        mockMvc.perform(asCustomer(customer, post("/api/scheduled-payments/{id}/cancel", payment)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        makeDueToday(payment);
        runner.run();
        assertThat(jdbc.queryForObject("select last_sent_on from scheduled_payments where id = ?", LocalDate.class,
                payment)).as("a cancelled payment is never sent").isNull();

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", from))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from scheduled_payments where id = ?", Integer.class, payment))
                .isZero();
    }

    private void makeDueToday(long payment) {
        jdbc.update("update scheduled_payments set next_payment_on = ? where id = ?",
                LocalDate.now(ZoneOffset.UTC), payment);
    }

    private int notSentEvents(long account) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload like ?",
                Integer.class, String.valueOf(account), "%\"SCHEDULED_PAYMENT_NOT_SENT\"%");
    }

    private ResultActions setUp(long customer, long from, String to, String amount, int day, String reference)
            throws Exception {
        return mockMvc.perform(asCustomer(customer, post("/api/scheduled-payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(from, to, amount, day, reference)));
    }

    private static String body(long from, String to, String amount, int day, String reference) {
        return "{\"fromAccountId\": " + from + ", \"toAccountNumber\": \"" + to + "\", \"amount\": " + amount
                + ", \"dayOfMonth\": " + day + (reference == null ? "" : ", \"reference\": \"" + reference + "\"") + "}";
    }

    private long createAccount(long customer) throws Exception {
        return idOf(mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}"))
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

    private String numberOf(long account) {
        return jdbc.queryForObject("select account_number from accounts where id = ?", String.class, account);
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
