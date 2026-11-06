package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.ResourceNotFoundException;
import com.bank.common.idempotency.IdempotencyFilter;
import com.bank.common.security.InternalTokens;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the service against real PostgreSQL, through its real security rules. Requires Docker. The
 * customer service is stubbed; e2e-tests covers the services working together.
 *
 * <p>The tests are deliberately not @Transactional: commits, rollbacks and constraint failures at
 * commit time are what is being tested. Each test creates its own account instead.
 */
@SpringBootTest(properties = {
        "bank.internal.token-secret=a-test-secret-that-is-long-enough-for-hs256",
        "bank.kafka.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountApiIntegrationTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InternalTokens tokens;

    @Autowired
    private IdempotencyFilter idempotency;

    @MockitoBean
    private CustomerServiceClient customerService;

    @Test
    void migrationsApplyToAnEmptyDatabase() {
        // Reaching this test also means Hibernate validated the entities against the schema.
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Long.class)).isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Long.class)).isZero();
    }

    @Test
    void depositAndWithdrawalUpdateTheBalanceAndTheLedger() throws Exception {
        long account = createAccount(newCustomerId());

        move(account, "deposit", "100.50").andExpect(status().isOk());
        move(account, "withdraw", "25.25")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(75.25));

        assertThat(balance(account)).isEqualByComparingTo("75.25");
        mockMvc.perform(asStaff(get("/api/accounts/{id}/transactions", account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(75.25))
                .andExpect(jsonPath("$.content[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.content[1].balanceAfter").value(100.5));
    }

    @Test
    void overdraftChangesNothing() throws Exception {
        long account = createAccount(newCustomerId());
        move(account, "deposit", "10.00").andExpect(status().isOk());

        move(account, "withdraw", "10.01").andExpect(status().isUnprocessableContent());

        assertThat(balance(account)).isEqualByComparingTo("10.00");
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    @Test
    void depositThatOverflowsTheBalanceColumnIsRolledBack() throws Exception {
        long account = createAccount(newCustomerId());
        String largestAmount = "99999999999999999.99";
        move(account, "deposit", largestAmount).andExpect(status().isOk());

        move(account, "deposit", largestAmount).andExpect(status().is4xxClientError());

        assertThat(balance(account)).isEqualByComparingTo(largestAmount);
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    @Test
    void accountWithHistoryCannotBeDeletedEvenAtZeroBalance() throws Exception {
        long account = createAccount(newCustomerId());
        move(account, "deposit", "5.00").andExpect(status().isOk());
        move(account, "withdraw", "5.00").andExpect(status().isOk());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isConflict());

        mockMvc.perform(asStaff(get("/api/accounts/{id}", account))).andExpect(status().isOk());
    }

    @Test
    void unusedAccountCanBeDeleted() throws Exception {
        long account = createAccount(newCustomerId());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isNoContent());

        mockMvc.perform(asStaff(get("/api/accounts/{id}", account))).andExpect(status().isNotFound());
    }

    @Test
    void unknownSortPropertyOnADerivedQueryReturns400() throws Exception {
        long account = createAccount(newCustomerId());

        mockMvc.perform(asStaff(get("/api/accounts/{id}/transactions", account)).param("sort", "nope"))
                .andExpect(status().isBadRequest());
    }

    /** Requests that lose the optimistic lock get a 409, so only accepted deposits may count. */
    @Test
    void simultaneousDepositsNeverLoseMoney() throws Exception {
        long account = createAccount(newCustomerId());

        List<Integer> statuses = runSimultaneously(List.of(
                deposit(account), deposit(account), deposit(account), deposit(account), deposit(account),
                deposit(account), deposit(account), deposit(account), deposit(account), deposit(account),
                deposit(account), deposit(account), deposit(account), deposit(account), deposit(account),
                deposit(account), deposit(account), deposit(account), deposit(account), deposit(account)));

        assertThat(statuses).containsAnyOf(200).isSubsetOf(200, 409);
        long accepted = Collections.frequency(statuses, 200);
        assertThat(balance(account)).isEqualByComparingTo(BigDecimal.valueOf(accepted));
        assertThat(ledgerEntries(account)).isEqualTo(accepted);
    }

    @Test
    void databaseRejectsANegativeBalanceEvenWithoutTheApplication() throws Exception {
        long account = createAccount(newCustomerId());

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -1 where id = ?", account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // What the customer service used to get from a shared database.

    @Test
    void accountForAnUnknownCustomerIsRefused() throws Exception {
        long customer = newCustomerId();
        doThrow(new ResourceNotFoundException("Customer " + customer + " not found."))
                .when(customerService).requireExists(customer);

        postAccount(customer).andExpect(status().isNotFound());

        assertThat(accountsOf(customer)).isZero();
    }

    @Test
    void accountCannotBeOpenedWhileTheCustomerServiceIsDown() throws Exception {
        long customer = newCustomerId();
        doThrow(new ResourceAccessException("down", new IOException())).when(customerService).requireExists(customer);

        postAccount(customer).andExpect(status().isServiceUnavailable());

        assertThat(accountsOf(customer)).isZero();
    }

    @Test
    void customerWithAccountsCannotBeRetired() throws Exception {
        long customer = newCustomerId();
        createAccount(customer);

        retire(customer).andExpect(status().isConflict());

        // Refusing must not block them: they are still a customer.
        postAccount(customer).andExpect(status().isCreated());
    }

    @Test
    void retiredCustomerCannotGetAnAccount() throws Exception {
        long customer = newCustomerId();
        retire(customer).andExpect(status().isNoContent());
        retire(customer).andExpect(status().isNoContent()); // a delete that is retried

        postAccount(customer).andExpect(status().isNotFound());

        assertThat(accountsOf(customer)).isZero();
    }

    /** The race the foreign key used to win: whichever goes first, the other must lose. */
    @Test
    void openingAnAccountAndRetiringTheCustomerNeverBothSucceed() throws Exception {
        for (int round = 0; round < 30; round++) {
            long customer = newCustomerId();

            List<Integer> statuses = runSimultaneously(List.of(
                    () -> postAccount(customer).andReturn().getResponse().getStatus(),
                    () -> retire(customer).andReturn().getResponse().getStatus()));

            if (statuses.get(0) == 201) {
                assertThat(statuses.get(1)).isEqualTo(409);
                assertThat(accountsOf(customer)).isEqualTo(1);
            } else {
                assertThat(statuses).containsExactly(404, 204);
                assertThat(accountsOf(customer)).isZero();
            }
        }
    }

    // Access rules.

    @Test
    void customerSeesOnlyTheirOwnAccounts() throws Exception {
        long ada = newCustomerId();
        long account = createAccount(ada);
        long other = createAccount(newCustomerId());
        String token = tokens.forUser(1L, "CUSTOMER", ada);

        mockMvc.perform(as(token, get("/api/accounts/{id}", account))).andExpect(status().isOk());
        mockMvc.perform(as(token, get("/api/accounts").param("customerId", String.valueOf(ada))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));

        mockMvc.perform(as(token, get("/api/accounts/{id}", other))).andExpect(status().isForbidden());
        mockMvc.perform(as(token, get("/api/accounts/{id}/transactions", other))).andExpect(status().isForbidden());
        mockMvc.perform(as(token, get("/api/accounts"))).andExpect(status().isForbidden());
        mockMvc.perform(as(token, get("/api/accounts/{id}", Long.MAX_VALUE))).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotMoveMoney() throws Exception {
        long ada = newCustomerId();
        long account = createAccount(ada);

        mockMvc.perform(as(tokens.forUser(1L, "CUSTOMER", ada), post("/api/accounts/{id}/deposit", account))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1.00}"))
                .andExpect(status().isForbidden());

        assertThat(balance(account)).isEqualByComparingTo("0");
    }

    @Test
    void nothingWorksWithoutAValidToken() throws Exception {
        mockMvc.perform(get("/api/accounts/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(as("not-a-token", get("/api/accounts/1"))).andExpect(status().isUnauthorized());

        String forged = new InternalTokens("a-different-secret-that-is-long-enough-too", "evil").forUser(1L, "STAFF", null);
        mockMvc.perform(as(forged, get("/api/accounts"))).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOtherServicesMayUseInternalEndpoints() throws Exception {
        long customer = newCustomerId();

        mockMvc.perform(put("/internal/retired-customers/{id}", customer)).andExpect(status().isUnauthorized());
        mockMvc.perform(asStaff(put("/internal/retired-customers/{id}", customer))).andExpect(status().isForbidden());

        postAccount(customer).andExpect(status().isCreated());
    }

    @Test
    void depositRepeatedWithTheSameKeyIsAppliedOnce() throws Exception {
        long account = createAccount(newCustomerId());

        String first = moveWithKey(account, "deposit", "10.00", "key-1")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER))
                .andReturn().getResponse().getContentAsString();
        String repeat = moveWithKey(account, "deposit", "10.00", "key-1")
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andReturn().getResponse().getContentAsString();

        assertThat(repeat).isEqualTo(first);
        assertThat(balance(account)).isEqualByComparingTo("10.00");
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    /** The case keys are for: the answer was lost, so the client sends it again while it may still run. */
    @Test
    void simultaneousRepeatsWithTheSameKeyAreAppliedOnce() throws Exception {
        long account = createAccount(newCustomerId());
        Callable<Integer> deposit = () ->
                moveWithKey(account, "deposit", "1.00", "same-key").andReturn().getResponse().getStatus();

        List<Integer> statuses = runSimultaneously(Collections.nCopies(8, deposit));

        assertThat(statuses).containsOnly(200);
        assertThat(balance(account)).isEqualByComparingTo("1.00");
        assertThat(ledgerEntries(account)).isEqualTo(1);
    }

    @Test
    void keyReusedForADifferentRequestIsRefused() throws Exception {
        long account = createAccount(newCustomerId());
        moveWithKey(account, "deposit", "10.00", "key-2").andExpect(status().isOk());

        moveWithKey(account, "deposit", "99.00", "key-2").andExpect(status().isUnprocessableContent());
        moveWithKey(account, "withdraw", "10.00", "key-2").andExpect(status().isUnprocessableContent());

        assertThat(balance(account)).isEqualByComparingTo("10.00");
    }

    /** A failure changed nothing, so it is not remembered: the same request can work later. */
    @Test
    void failedRequestCanBeRetriedWithTheSameKey() throws Exception {
        long account = createAccount(newCustomerId());
        moveWithKey(account, "withdraw", "5.00", "key-3").andExpect(status().isUnprocessableContent());

        move(account, "deposit", "8.00").andExpect(status().isOk());
        moveWithKey(account, "withdraw", "5.00", "key-3")
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        assertThat(balance(account)).isEqualByComparingTo("3.00");
    }

    @Test
    void openingAnAccountRepeatedWithTheSameKeyOpensOne() throws Exception {
        long customer = newCustomerId();
        String location = postAccountWithKey(customer, "open-1").andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        postAccountWithKey(customer, "open-1")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, location))
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

        assertThat(accountsOf(customer)).isEqualTo(1);
    }

    /** Keys belong to the caller, so another person's request is never answered with someone else's. */
    @Test
    void theSameKeyFromAnotherCallerIsANewRequest() throws Exception {
        long account = createAccount(newCustomerId());
        moveWithKey(account, "deposit", "1.00", "shared").andExpect(status().isOk());

        mockMvc.perform(as(tokens.forUser(1L, "STAFF", null), post("/api/accounts/" + account + "/deposit"))
                        .header(IdempotencyFilter.KEY_HEADER, "shared")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1.00}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));

        assertThat(balance(account)).isEqualByComparingTo("2.00");
    }

    @Test
    void malformedKeyIsRefused() throws Exception {
        long account = createAccount(newCustomerId());

        moveWithKey(account, "deposit", "1.00", "has spaces").andExpect(status().isBadRequest());
        moveWithKey(account, "deposit", "1.00", "x".repeat(101)).andExpect(status().isBadRequest());

        assertThat(balance(account)).isEqualByComparingTo("0");
    }

    @Test
    void expiredKeysAreRemoved() throws Exception {
        long account = createAccount(newCustomerId());
        moveWithKey(account, "deposit", "1.00", "old-key").andExpect(status().isOk());
        jdbc.update("update idempotency_keys set created_at = now() - interval '25 hours' where idem_key = 'old-key'");

        idempotency.removeExpiredKeys();

        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = 'old-key'", Long.class))
                .isZero();
    }

    @Test
    void healthIsPublicForContainerChecksButOtherEndpointsAreNot() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    private static long newCustomerId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return as(tokens.forUser(0L, "STAFF", null), request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private long createAccount(long customerId) throws Exception {
        Number id = JsonPath.read(postAccount(customerId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private ResultActions postAccount(long customerId) throws Exception {
        return mockMvc.perform(asStaff(post("/api/accounts"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": " + customerId + ", \"type\": \"CHECKING\"}"));
    }

    private ResultActions retire(long customerId) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), put("/internal/retired-customers/{id}", customerId)));
    }

    private ResultActions move(long accountId, String operation, String amount) throws Exception {
        return mockMvc.perform(asStaff(post("/api/accounts/" + accountId + "/" + operation))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions moveWithKey(long accountId, String operation, String amount, String key) throws Exception {
        return mockMvc.perform(asStaff(post("/api/accounts/" + accountId + "/" + operation))
                .header(IdempotencyFilter.KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions postAccountWithKey(long customerId, String key) throws Exception {
        return mockMvc.perform(asStaff(post("/api/accounts"))
                .header(IdempotencyFilter.KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": " + customerId + ", \"type\": \"CHECKING\"}"));
    }

    private Callable<Integer> deposit(long account) {
        return () -> move(account, "deposit", "1.00").andReturn().getResponse().getStatus();
    }

    private BigDecimal balance(long accountId) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, accountId);
    }

    private long ledgerEntries(long accountId) {
        return jdbc.queryForObject(
                "select count(*) from account_transactions where account_id = ?", Long.class, accountId);
    }

    private long accountsOf(long customerId) {
        return jdbc.queryForObject("select count(*) from accounts where customer_id = ?", Long.class, customerId);
    }

    /** Releases all requests at once, so that they really do overlap. Statuses are in request order. */
    private static List<Integer> runSimultaneously(List<Callable<Integer>> requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(requests.size())) {
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<Integer> request : requests) {
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
}
