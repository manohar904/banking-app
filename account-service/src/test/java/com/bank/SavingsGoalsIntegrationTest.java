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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Savings goals, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = "bank.kafka.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class SavingsGoalsIntegrationTest {

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
    void aGoalFollowsTheAccountsBalanceUntilItIsReached() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer, "SAVINGS");
        LocalDate by = LocalDate.now(ZoneOffset.UTC).plusMonths(3);

        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", account)))
                .andExpect(status().isNoContent());

        mockMvc.perform(setGoal(customer, account, "{\"name\": \" Holiday \", \"targetAmount\": 2000, \"targetDate\": \"" + by + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Holiday"))
                .andExpect(jsonPath("$.targetAmount").value(2000.00))
                .andExpect(jsonPath("$.targetDate").value(by.toString()))
                .andExpect(jsonPath("$.percent").value(0))
                .andExpect(jsonPath("$.remaining").value(2000.00))
                .andExpect(jsonPath("$.perMonth").value(500.00)); // this month and the next three

        deposit(account, "1250.00");
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(1250.00))
                .andExpect(jsonPath("$.remaining").value(750.00))
                .andExpect(jsonPath("$.percent").value(62))
                .andExpect(jsonPath("$.reached").value(false));

        // Changing it keeps one goal for the account.
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"Car\", \"targetAmount\": 1000.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Car"))
                .andExpect(jsonPath("$.targetDate").doesNotExist())
                .andExpect(jsonPath("$.reached").value(true))
                .andExpect(jsonPath("$.percent").value(100));
        assertThat(jdbc.queryForObject("select count(*) from savings_goals where account_id = ?", Integer.class, account))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload like ?",
                Integer.class, String.valueOf(account), "%\"SAVINGS_GOAL_SET\"%")).isEqualTo(2);

        mockMvc.perform(asCustomer(customer, delete("/api/accounts/{id}/goal", account)))
                .andExpect(status().isNoContent());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", account)))
                .andExpect(status().isNoContent());
        // Removing one that is already gone is fine.
        mockMvc.perform(asCustomer(customer, delete("/api/accounts/{id}/goal", account)))
                .andExpect(status().isNoContent());
    }

    @Test
    void staffCanSetOneButOnlyOnSavingsAccountsAndOnlyItsOwnerSeesIt() throws Exception {
        long customer = newCustomerId();
        long savings = createAccount(customer, "SAVINGS");
        long checking = createAccount(customer, "CHECKING");

        mockMvc.perform(asStaff(put("/api/accounts/{id}/goal", savings)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Emergency fund\", \"targetAmount\": 3000}"))
                .andExpect(status().isOk());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", savings)))
                .andExpect(jsonPath("$.name").value("Emergency fund"));

        mockMvc.perform(setGoal(customer, checking, "{\"name\": \"Holiday\", \"targetAmount\": 100}"))
                .andExpect(status().isBadRequest());

        long stranger = newCustomerId();
        mockMvc.perform(asCustomer(stranger, get("/api/accounts/{id}/goal", savings))).andExpect(status().isForbidden());
        mockMvc.perform(setGoal(stranger, savings, "{\"name\": \"Mine now\", \"targetAmount\": 1}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(asCustomer(stranger, delete("/api/accounts/{id}/goal", savings))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/{id}/goal", savings)).andExpect(status().isUnauthorized());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", savings)))
                .andExpect(jsonPath("$.name").value("Emergency fund"));
    }

    @Test
    void aGoalNeedsANameAPositiveAmountAndADateStillToCome() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer, "SAVINGS");
        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();

        mockMvc.perform(setGoal(customer, account, "{\"name\": \" \", \"targetAmount\": 100}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"" + "x".repeat(41) + "\", \"targetAmount\": 100}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"Holiday\", \"targetAmount\": 0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.targetAmount").exists());
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"Holiday\", \"targetAmount\": 10.005}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.targetAmount").exists());
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"Holiday\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.targetAmount").exists());
        mockMvc.perform(setGoal(customer, account,
                        "{\"name\": \"Holiday\", \"targetAmount\": 100, \"targetDate\": \"" + yesterday + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/goal", account)))
                .andExpect(status().isNoContent());
    }

    @Test
    void deletingTheAccountRemovesItsGoal() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer, "SAVINGS");
        mockMvc.perform(setGoal(customer, account, "{\"name\": \"Holiday\", \"targetAmount\": 100}"))
                .andExpect(status().isOk());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from savings_goals where account_id = ?", Integer.class, account))
                .isZero();
    }

    private MockHttpServletRequestBuilder setGoal(long customer, long account, String json) {
        return asCustomer(customer, put("/api/accounts/{id}/goal", account))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private long createAccount(long customer, String type) throws Exception {
        String json = mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private void deposit(long account, String amount) throws Exception {
        mockMvc.perform(asStaff(post("/api/accounts/{id}/deposit", account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}")).andExpect(status().isOk());
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
