package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.common.ConflictException;
import com.bank.common.security.InternalTokens;
import com.bank.customer.AccountServiceClient;
import com.bank.customer.AuthServiceClient;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * other services are stubbed; e2e-tests covers them working together.
 */
@SpringBootTest(properties = "bank.internal.token-secret=a-test-secret-that-is-long-enough-for-hs256")
@AutoConfigureMockMvc
@Testcontainers
class CustomerApiIntegrationTest {

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

    @MockitoBean
    private AccountServiceClient accountService;

    @MockitoBean
    private AuthServiceClient authService;

    @Test
    void migrationsApplyToAnEmptyDatabase() {
        // Reaching this test also means Hibernate validated the entities against the schema.
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history", Long.class)).isPositive();
    }

    @Test
    void emailsAreUniqueRegardlessOfCase() throws Exception {
        String email = uniqueEmail();
        postCustomer(email.toUpperCase()).andExpect(status().isCreated());

        postCustomer(email).andExpect(status().isConflict());

        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    /** Requests can pass the existsByEmail check together. The unique constraint must stop them. */
    @Test
    void simultaneousSignupsCreateOneCustomer() throws Exception {
        String email = uniqueEmail();
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return postCustomer(email).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                statuses.add(result.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(Collections.frequency(statuses, 201)).isEqualTo(1);
        assertThat(Collections.frequency(statuses, 409)).isEqualTo(19);
        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    @Test
    void unknownSortPropertyReturns400() throws Exception {
        mockMvc.perform(asStaff(get("/api/customers")).param("sort", "nope")).andExpect(status().isBadRequest());
    }

    // Deleting spans three services.

    @Test
    void deleteRetiresTheCustomerThenRemovesTheLoginThenTheRow() throws Exception {
        long customer = createCustomer();

        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isNoContent());

        InOrder order = inOrder(accountService, authService);
        order.verify(accountService).retireCustomer(customer);
        order.verify(authService).deleteLogin(customer);
        mockMvc.perform(asStaff(get("/api/customers/{id}", customer))).andExpect(status().isNotFound());
    }

    @Test
    void customerWithAccountsIsNotDeletedAndKeepsTheirLogin() throws Exception {
        long customer = createCustomer();
        doThrow(new ConflictException("A customer who still has accounts cannot be deleted."))
                .when(accountService).retireCustomer(customer);

        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isConflict());

        verify(authService, never()).deleteLogin(customer);
        mockMvc.perform(asStaff(get("/api/customers/{id}", customer))).andExpect(status().isOk());
    }

    @Test
    void deleteThatFailsPartWayKeepsTheCustomerAndCanBeRetried() throws Exception {
        long customer = createCustomer();
        doThrow(new ResourceAccessException("down", new IOException())).when(authService).deleteLogin(customer);

        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isServiceUnavailable());
        mockMvc.perform(asStaff(get("/api/customers/{id}", customer))).andExpect(status().isOk());

        doNothing().when(authService).deleteLogin(customer);
        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isNoContent());
        mockMvc.perform(asStaff(get("/api/customers/{id}", customer))).andExpect(status().isNotFound());
    }

    @Test
    void deletingAnUnknownCustomerReturns404AndCallsNobody() throws Exception {
        mockMvc.perform(asStaff(delete("/api/customers/{id}", Long.MAX_VALUE))).andExpect(status().isNotFound());

        verify(accountService, never()).retireCustomer(Long.MAX_VALUE);
    }

    // Changing an email spans two services.

    @Test
    void emailChangeReachesTheLogin() throws Exception {
        long customer = createCustomer();
        String newEmail = uniqueEmail();

        putCustomer(customer, newEmail.toUpperCase()).andExpect(status().isOk());

        verify(authService).changeLoginEmail(customer, newEmail);
        assertThat(customersWithEmail(newEmail)).isEqualTo(1);
    }

    @Test
    void emailTakenByAStaffLoginRollsTheCustomerBack() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        String staffEmail = uniqueEmail();
        doThrow(new ConflictException("This email is already used by another login."))
                .when(authService).changeLoginEmail(eq(customer), anyString());

        putCustomer(customer, staffEmail).andExpect(status().isConflict());

        assertThat(customersWithEmail(staffEmail)).isZero();
        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    @Test
    void emailChangeRollsBackIfTheAuthServiceIsDown() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        String newEmail = uniqueEmail();
        doThrow(new ResourceAccessException("down", new IOException()))
                .when(authService).changeLoginEmail(eq(customer), anyString());

        putCustomer(customer, newEmail).andExpect(status().isServiceUnavailable());

        assertThat(customersWithEmail(newEmail)).isZero();
        assertThat(customersWithEmail(email)).isEqualTo(1);
    }

    // Access rules.

    @Test
    void customerSeesOnlyThemselves() throws Exception {
        long ada = createCustomer();
        long other = createCustomer();
        String token = tokens.forUser(1L, "CUSTOMER", ada);

        mockMvc.perform(as(token, get("/api/customers/{id}", ada)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ada));
        mockMvc.perform(as(token, get("/api/customers/{id}", other))).andExpect(status().isForbidden());
        mockMvc.perform(as(token, get("/api/customers"))).andExpect(status().isForbidden());
        mockMvc.perform(as(token, delete("/api/customers/{id}", ada))).andExpect(status().isForbidden());
    }

    @Test
    void onlyOtherServicesMayUseInternalEndpoints() throws Exception {
        long customer = createCustomer();

        mockMvc.perform(get("/internal/customers/{id}", customer)).andExpect(status().isUnauthorized());
        mockMvc.perform(asStaff(get("/internal/customers/{id}", customer))).andExpect(status().isForbidden());
        mockMvc.perform(as(tokens.forThisService(), get("/internal/customers/{id}", customer)))
                .andExpect(status().isOk());
    }

    @Test
    void serviceTokenDoesNotOpenThePublicApi() throws Exception {
        mockMvc.perform(as(tokens.forThisService(), get("/api/customers"))).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return as(tokens.forUser(0L, "STAFF", null), request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private long createCustomer() throws Exception {
        return createCustomer(uniqueEmail());
    }

    private long createCustomer(String email) throws Exception {
        Number id = JsonPath.read(postCustomer(email).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private ResultActions postCustomer(String email) throws Exception {
        return mockMvc.perform(asStaff(post("/api/customers"))
                .contentType(MediaType.APPLICATION_JSON).content(customerJson(email)));
    }

    private ResultActions putCustomer(long id, String email) throws Exception {
        return mockMvc.perform(asStaff(put("/api/customers/{id}", id))
                .contentType(MediaType.APPLICATION_JSON).content(customerJson(email)));
    }

    private long customersWithEmail(String email) {
        return jdbc.queryForObject("select count(*) from customers where email = ?", Long.class, email);
    }

    private static String customerJson(String email) {
        return "{\"firstName\": \"Ada\", \"lastName\": \"Lovelace\", \"email\": \"" + email + "\"}";
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
