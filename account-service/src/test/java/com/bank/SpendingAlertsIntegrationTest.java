package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
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

/** Spending alerts, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = "bank.kafka.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class SpendingAlertsIntegrationTest {

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

    @MockitoBean
    private CustomerServiceClient customerService;

    @Test
    void goingOverTheAmountRecordsOneEventForTheMonth() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "500.00");

        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isNoContent());
        mockMvc.perform(setAlert(customer, account, "{\"amount\": 100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.spentThisMonth").value(0.00))
                .andExpect(jsonPath("$.passed").value(false));

        withdraw(account, "60.00");
        assertThat(passedEvents(account)).isEmpty();
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(jsonPath("$.spentThisMonth").value(60.00))
                .andExpect(jsonPath("$.remaining").value(40.00))
                .andExpect(jsonPath("$.percent").value(60));

        withdraw(account, "40.00"); // exactly the amount is not over it
        assertThat(passedEvents(account)).isEmpty();

        withdraw(account, "10.50");
        assertThat(passedEvents(account)).singleElement().satisfies(payload -> {
            assertThat(JsonPath.<Number>read(payload, "$.data.amount").doubleValue()).isEqualTo(110.50);
            assertThat(JsonPath.<Number>read(payload, "$.data.alertAmount").doubleValue()).isEqualTo(100.00);
            assertThat(JsonPath.<Number>read(payload, "$.data.customerId").longValue()).isEqualTo(customer);
            assertThat(JsonPath.<String>read(payload, "$.data.accountEnding")).hasSize(4);
        });

        withdraw(account, "5.00");
        assertThat(passedEvents(account)).hasSize(1); // once a month, not on every payment after
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(jsonPath("$.spentThisMonth").value(115.50))
                .andExpect(jsonPath("$.remaining").value(0.00))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    void transfersOutCountMoneyInDoesNotAndANewAmountStartsAfresh() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        long other = createAccount(newCustomerId());
        deposit(account, "1000.00");
        deposit(other, "1000.00");
        mockMvc.perform(setAlert(customer, account, "{\"amount\": 200}")).andExpect(status().isOk());

        transfer(other, accountNumber(account), "300.00"); // money in: not spending
        transfer(account, accountNumber(other), "150.00");
        assertThat(passedEvents(account)).isEmpty();
        transfer(account, accountNumber(other), "60.00");
        assertThat(passedEvents(account)).hasSize(1);
        assertThat(passedEvents(other)).isEmpty();

        // Raising the amount after being told: going over the new one tells them again.
        mockMvc.perform(setAlert(customer, account, "{\"amount\": 250}"))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.remaining").value(40.00));
        withdraw(account, "50.00");
        assertThat(passedEvents(account)).hasSize(2);

        mockMvc.perform(asCustomer(customer, delete("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isNoContent());
        withdraw(account, "50.00");
        assertThat(passedEvents(account)).hasSize(2);
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isNoContent());
        // Removing one that is already gone is fine.
        mockMvc.perform(asCustomer(customer, delete("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isNoContent());
    }

    @Test
    void onlyTheOwnerAndStaffCanSeeOrChangeIt() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        mockMvc.perform(asStaff(put("/api/accounts/{id}/spending-alert", account))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 750}"))
                .andExpect(status().isOk());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(jsonPath("$.amount").value(750.00));

        long stranger = newCustomerId();
        mockMvc.perform(asCustomer(stranger, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isForbidden());
        mockMvc.perform(setAlert(stranger, account, "{\"amount\": 1}")).andExpect(status().isForbidden());
        mockMvc.perform(asCustomer(stranger, delete("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/{id}/spending-alert", account)).andExpect(status().isUnauthorized());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(jsonPath("$.amount").value(750.00));
    }

    @Test
    void theAmountMustBePositiveWithAtMostTwoDecimals() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        for (String json : List.of("{\"amount\": 0}", "{\"amount\": -5}", "{\"amount\": 10.005}", "{}")) {
            mockMvc.perform(setAlert(customer, account, json))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.amount").exists());
        }
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/spending-alert", account)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingTheAccountRemovesItsAlert() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        mockMvc.perform(setAlert(customer, account, "{\"amount\": 100}")).andExpect(status().isOk());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from spending_alerts where account_id = ?", Integer.class,
                account)).isZero();
    }

    private List<String> passedEvents(long account) {
        return jdbc.queryForList("select payload from outbox_events where aggregate_id = ? and payload like ? order by id",
                String.class, String.valueOf(account), "%\"SPENDING_ALERT_PASSED\"%");
    }

    private MockHttpServletRequestBuilder setAlert(long customer, long account, String json) {
        return asCustomer(customer, put("/api/accounts/{id}/spending-alert", account))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private long createAccount(long customer) throws Exception {
        String json = mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private String accountNumber(long account) {
        return jdbc.queryForObject("select account_number from accounts where id = ?", String.class, account);
    }

    private void deposit(long account, String amount) throws Exception {
        move(account, "deposit", "{\"amount\": " + amount + "}");
    }

    private void withdraw(long account, String amount) throws Exception {
        move(account, "withdraw", "{\"amount\": " + amount + "}");
    }

    private void transfer(long from, String toAccountNumber, String amount) throws Exception {
        move(from, "transfer", "{\"toAccountNumber\": \"" + toAccountNumber + "\", \"amount\": " + amount + "}");
    }

    private void move(long account, String action, String json) throws Exception {
        mockMvc.perform(asStaff(post("/api/accounts/{id}/" + action, account)).contentType(MediaType.APPLICATION_JSON)
                .content(json)).andExpect(status().isOk());
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
