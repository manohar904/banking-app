package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.auth.BankUserDetails;
import com.bank.auth.Role;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the whole application against real PostgreSQL. Requires Docker.
 *
 * <p>The tests are deliberately not @Transactional: commits, rollbacks and constraint failures at
 * commit time are what is being tested. Each test creates its own customer and account instead.
 */
@SpringBootTest
@Testcontainers
class BankingApiIntegrationTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    /** Every request is made as signed-in staff. Access rules are tested in SecurityIntegrationTest. */
    @BeforeEach
    void signInAsStaff() {
        BankUserDetails staff = new BankUserDetails(0L, "staff@example.com", "unused", Role.STAFF, null);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(user(staff)).with(csrf()))
                .build();
    }

    @Test
    void migrationsApplyToAnEmptyDatabase() {
        // Reaching this test also means Hibernate validated the entities against the schema.
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Long.class)).isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Long.class)).isZero();
    }

    @Test
    void depositAndWithdrawalUpdateTheBalanceAndTheLedger() throws Exception {
        long account = createAccount(createCustomer());

        move(account, "deposit", "100.50").andExpect(status().isOk());
        move(account, "withdraw", "25.25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(75.25));

        assertThat(balance(account)).isEqualByComparingTo("75.25");
        mockMvc.perform(get("/api/accounts/{id}/transactions", account))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(75.25))
                .andExpect(jsonPath("$.content[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(100.5));
    }

    @Test
    void overdraftChangesNothing() throws Exception {
        long account = createAccount(createCustomer());
        move(account, "deposit", "10.00").andExpect(status().isOk());

        move(account, "withdraw", "10.01").andExpect(status().isUnprocessableContent());

        assertThat(balance(account)).isEqualByComparingTo("10.00");
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    @Test
    void depositThatOverflowsTheBalanceColumnIsRolledBack() throws Exception {
        long account = createAccount(createCustomer());
        String largestAmount = "99999999999999999.99";
        move(account, "deposit", largestAmount).andExpect(status().isOk());

        move(account, "deposit", largestAmount).andExpect(status().is4xxClientError());

        assertThat(balance(account)).isEqualByComparingTo(largestAmount);
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    @Test
    void emailsAreUniqueRegardlessOfCase() throws Exception {
        String email = uniqueEmail();
        postJson("/api/customers", customerJson(email.toUpperCase())).andExpect(status().isCreated());

        postJson("/api/customers", customerJson(email)).andExpect(status().isConflict());

        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    @Test
    void customerWithAccountsCannotBeDeleted() throws Exception {
        long customer = createCustomer();
        createAccount(customer);

        // The foreign key fails at commit, after the service method has returned.
        mockMvc.perform(delete("/api/customers/{id}", customer)).andExpect(status().isConflict());

        mockMvc.perform(get("/api/customers/{id}", customer)).andExpect(status().isOk());
    }

    @Test
    void accountWithHistoryCannotBeDeletedEvenAtZeroBalance() throws Exception {
        long account = createAccount(createCustomer());
        move(account, "deposit", "5.00").andExpect(status().isOk());
        move(account, "withdraw", "5.00").andExpect(status().isOk());

        mockMvc.perform(delete("/api/accounts/{id}", account)).andExpect(status().isConflict());

        mockMvc.perform(get("/api/accounts/{id}", account)).andExpect(status().isOk());
    }

    @Test
    void unusedAccountAndCustomerCanBeDeleted() throws Exception {
        long customer = createCustomer();
        long account = createAccount(customer);

        mockMvc.perform(delete("/api/accounts/{id}", account)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/customers/{id}", customer)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/accounts/{id}", account)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/customers/{id}", customer)).andExpect(status().isNotFound());
    }

    @Test
    void unknownSortPropertyOnADerivedQueryReturns400() throws Exception {
        long account = createAccount(createCustomer());

        mockMvc.perform(get("/api/accounts/{id}/transactions", account).param("sort", "nope"))
                .andExpect(status().isBadRequest());
    }

    /** Requests that lose the optimistic lock get a 409, so only accepted deposits may count. */
    @Test
    void simultaneousDepositsNeverLoseMoney() throws Exception {
        long account = createAccount(createCustomer());

        List<Integer> statuses = runSimultaneously(20,
                () -> move(account, "deposit", "1.00").andReturn().getResponse().getStatus());

        assertThat(statuses).containsAnyOf(200).isSubsetOf(200, 409);
        long accepted = Collections.frequency(statuses, 200);
        assertThat(balance(account)).isEqualByComparingTo(BigDecimal.valueOf(accepted));
        assertThat(ledgerEntries(account)).isEqualTo(accepted);
    }

    /** Requests can pass the existsByEmail check together. The unique constraint must stop them. */
    @Test
    void simultaneousSignupsCreateOneCustomer() throws Exception {
        String email = uniqueEmail();

        List<Integer> statuses = runSimultaneously(20,
                () -> postJson("/api/customers", customerJson(email)).andReturn().getResponse().getStatus());

        assertThat(Collections.frequency(statuses, 201)).isEqualTo(1);
        assertThat(Collections.frequency(statuses, 409)).isEqualTo(19);
        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    @Test
    void frontEndIsServedFromTheApplication() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mockMvc.perform(get("/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/styles.css")).andExpect(status().isOk());
    }

    @Test
    void databaseRejectsANegativeBalanceEvenWithoutTheApplication() throws Exception {
        long account = createAccount(createCustomer());

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -1 where id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long createCustomer() throws Exception {
        return idFrom(postJson("/api/customers", customerJson(uniqueEmail())).andExpect(status().isCreated()));
    }

    private long createAccount(long customerId) throws Exception {
        String json = "{\"customerId\": " + customerId + ", \"type\": \"CHECKING\"}";
        return idFrom(postJson("/api/accounts", json).andExpect(status().isCreated()));
    }

    private ResultActions move(long accountId, String operation, String amount) throws Exception {
        return postJson("/api/accounts/" + accountId + "/" + operation, "{\"amount\": " + amount + "}");
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    private long ledgerEntries(long accountId) {
        return jdbc.queryForObject(
                "select count(*) from account_transactions where account_id = ?", Long.class, accountId);
    }

    private long customersWithEmail(String email) {
        return jdbc.queryForObject("select count(*) from customers where email = ?", Long.class, email);
    }

    /** Releases all requests at once, so that they really do overlap. */
    private static List<Integer> runSimultaneously(int requests, Callable<Integer> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        }
    }

    private static long idFrom(ResultActions result) throws Exception {
        Number id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private static String customerJson(String email) {
        return "{\"firstName\": \"Ada\", \"lastName\": \"Lovelace\", \"email\": \"" + email + "\"}";
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
