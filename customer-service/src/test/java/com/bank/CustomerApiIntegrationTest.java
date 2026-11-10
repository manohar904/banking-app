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
import com.bank.common.security.TestGateway;
import com.bank.customer.AccountServiceClient;
import com.bank.customer.AuthServiceClient;
import com.bank.customer.TransactionEmails;
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
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
@SpringBootTest(properties = {
        "bank.kafka.enabled=false",
        "bank.transaction-emails.from=bank@example.com"
})
@AutoConfigureMockMvc
@Testcontainers
class CustomerApiIntegrationTest {

    /** Same image as compose.yaml. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    /** Signs tokens for people, as the real gateway does. */
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
    private InternalTokens tokens;

    @MockitoBean
    private AccountServiceClient accountService;

    @MockitoBean
    private AuthServiceClient authService;

    @MockitoBean
    private JavaMailSender mail;

    @Autowired
    private TransactionEmails transactionEmails;

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
    void eventsCarryIdsButNoPersonalData() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isNoContent());

        List<String> events = jdbc.queryForList(
                "select payload from outbox_events where aggregate_id = ? order by id", String.class,
                String.valueOf(customer));

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).contains("CUSTOMER_CREATED").doesNotContain(email, "Lovelace");
        assertThat(events.get(1)).contains("CUSTOMER_DELETED");
    }

    @Test
    void refusedDeleteRecordsNoEvent() throws Exception {
        long customer = createCustomer();
        doThrow(new ConflictException("A customer who still has accounts cannot be deleted."))
                .when(accountService).retireCustomer(customer);

        mockMvc.perform(asStaff(delete("/api/customers/{id}", customer))).andExpect(status().isConflict());

        assertThat(jdbc.queryForList("select payload from outbox_events where aggregate_id = ?", String.class,
                String.valueOf(customer))).hasSize(1);
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
        String token = gateway.forUser(1L, "CUSTOMER", ada);

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

    @Test
    void auditLogSaysWhoCreatedACustomerAndHoldsNoPersonalDetails() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);

        String answer = mockMvc.perform(asStaff(get("/api/customers/audit").param("targetId", String.valueOf(customer))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("CUSTOMER_CREATED"))
                .andExpect(jsonPath("$.content[0].actorRole").value("STAFF"))
                .andReturn().getResponse().getContentAsString();

        assertThat(answer).doesNotContain(email);
    }

    @Test
    void customerCannotReadTheAuditLog() throws Exception {
        long ada = createCustomer();

        mockMvc.perform(as(gateway.forUser(1L, "CUSTOMER", ada), get("/api/customers/audit")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmailAddressIsRequiredForEveryCustomer() throws Exception {
        mockMvc.perform(asStaff(post("/api/customers")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\": \"Ada\", \"lastName\": \"Lovelace\"}"))
                .andExpect(status().isBadRequest());
        postCustomer("not-an-email").andExpect(status().isBadRequest());
    }

    @Test
    void transactionEmailsAreOnByDefaultAndCustomersCanSeeTheSetting() throws Exception {
        long ada = createCustomer();

        mockMvc.perform(as(gateway.forUser(1L, "CUSTOMER", ada), get("/api/customers/{id}", ada)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionEmailsEnabled").value(true));
    }

    @Test
    void staffCanTurnTransactionEmailsOffAndOnAndItIsAudited() throws Exception {
        long customer = createCustomer();

        setTransactionEmails(asStaff(put("/api/customers/{id}/transaction-emails", customer)), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionEmailsEnabled").value(false));
        setTransactionEmails(asStaff(put("/api/customers/{id}/transaction-emails", customer)), true)
                .andExpect(jsonPath("$.transactionEmailsEnabled").value(true));

        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'TRANSACTION_EMAILS_CHANGED' and target_id = ?",
                Long.class, String.valueOf(customer))).isEqualTo(2);
    }

    @Test
    void customerCannotChangeTheirTransactionEmailSetting() throws Exception {
        long ada = createCustomer();

        setTransactionEmails(as(gateway.forUser(1L, "CUSTOMER", ada), put("/api/customers/{id}/transaction-emails", ada)), false)
                .andExpect(status().isForbidden());

        assertThat(emailsEnabled(ada)).isTrue();
    }

    @Test
    void aDepositEmailsTheCustomerWithExactAmountsAndOnlyTheLastFourDigits() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);

        deliver("MONEY_DEPOSITED", customer, "12345678901234567.89", "99999999999999999.10");

        SimpleMailMessage message = emailsTo(email).getFirst();
        assertThat(message.getSubject()).isEqualTo("Deposit of 12,345,678,901,234,567.89 to your account ending 7265");
        assertThat(message.getFrom()).isEqualTo("bank@example.com");
        assertThat(message.getText()).contains("Hello Ada", "New balance: 99,999,999,999,999,999.10");
    }

    @Test
    void eachKindOfMovementIsWordedCorrectly() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);

        deliver("MONEY_WITHDRAWN", customer, "1.50", "2.00");
        deliver("TRANSFER_SENT", customer, "3.00", "4.00");
        deliver("TRANSFER_RECEIVED", customer, "5.00", "6.00");

        assertThat(emailsTo(email)).extracting(SimpleMailMessage::getSubject).containsExactlyInAnyOrder(
                "Withdrawal of 1.50 from your account ending 7265",
                "Transfer of 3.00 from your account ending 7265",
                "Transfer of 5.00 to your account ending 7265");
    }

    @Test
    void noEmailIsSentWhenStaffHaveTurnedThemOff() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        setTransactionEmails(asStaff(put("/api/customers/{id}/transaction-emails", customer)), false);

        deliver("MONEY_DEPOSITED", customer, "1.00", "1.00");

        assertThat(emailsTo(email)).isEmpty();
    }

    @Test
    void aRepeatedEventSendsOneEmail() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        String event = moneyEvent(UUID.randomUUID().toString(), "MONEY_DEPOSITED", customer, "1.00", "1.00");

        transactionEmails.handle(event);
        transactionEmails.handle(event);
        transactionEmails.sendPending();
        transactionEmails.handle(event);
        transactionEmails.sendPending();

        assertThat(emailsTo(email)).hasSize(1);
    }

    @Test
    void otherEventsAndUnreadableOnesSendNothing() {
        long before = jdbc.queryForObject("select count(*) from transaction_emails", Long.class);

        transactionEmails.handle("{\"eventId\":\"a\",\"type\":\"CUSTOMER_CREATED\",\"data\":{}}");
        transactionEmails.handle("{\"eventId\":\"b\",\"type\":\"MONEY_DEPOSITED\",\"data\":{\"amount\":1.00}}");
        transactionEmails.handle(moneyEvent("c", "MONEY_DEPOSITED", Long.MAX_VALUE, "1.00", "1.00"));
        transactionEmails.handle("not json");

        assertThat(jdbc.queryForObject("select count(*) from transaction_emails", Long.class)).isEqualTo(before);
    }

    @Test
    void anEmailThatFailsIsTriedAgainLater() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        doThrow(new MailSendException("down")).doNothing()
                .when(mail).send(org.mockito.ArgumentMatchers.argThat((SimpleMailMessage m) -> m != null && m.getTo()[0].equals(email)));
        String eventId = UUID.randomUUID().toString();
        transactionEmails.handle(moneyEvent(eventId, "MONEY_DEPOSITED", customer, "1.00", "1.00"));

        transactionEmails.sendPending();
        assertThat(sentAt(eventId)).isNull();

        // Not retried before its time.
        transactionEmails.sendPending();
        assertThat(emailsTo(email)).hasSize(1);

        jdbc.update("update transaction_emails set next_attempt_at = now() where event_id = ?", eventId);
        transactionEmails.sendPending();
        assertThat(sentAt(eventId)).isNotNull();
    }

    @Test
    void anEmailIsAbandonedAfterTooManyFailures() throws Exception {
        String email = uniqueEmail();
        long customer = createCustomer(email);
        String eventId = UUID.randomUUID().toString();
        transactionEmails.handle(moneyEvent(eventId, "MONEY_DEPOSITED", customer, "1.00", "1.00"));
        jdbc.update("update transaction_emails set attempts = 10 where event_id = ?", eventId);

        transactionEmails.sendPending();

        assertThat(emailsTo(email)).isEmpty();
    }

    private ResultActions setTransactionEmails(MockHttpServletRequestBuilder request, boolean enabled) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": " + enabled + "}"));
    }

    private boolean emailsEnabled(long customer) {
        return jdbc.queryForObject("select transaction_emails_enabled from customers where id = ?", Boolean.class, customer);
    }

    private java.sql.Timestamp sentAt(String eventId) {
        return jdbc.queryForObject("select sent_at from transaction_emails where event_id = ?", java.sql.Timestamp.class, eventId);
    }

    private void deliver(String type, long customer, String amount, String balance) {
        transactionEmails.handle(moneyEvent(UUID.randomUUID().toString(), type, customer, amount, balance));
        transactionEmails.sendPending();
    }

    /** The same shape account-service publishes. */
    private static String moneyEvent(String eventId, String type, long customer, String amount, String balance) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"data\":{\"amount\":" + amount
                + ",\"balanceAfter\":" + balance + ",\"customerId\":" + customer + ",\"accountEnding\":\"7265\"}}";
    }

    private List<SimpleMailMessage> emailsTo(String email) {
        return org.mockito.Mockito.mockingDetails(mail).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("send"))
                .map(invocation -> (SimpleMailMessage) invocation.getArgument(0))
                .filter(message -> message.getTo()[0].equals(email))
                .toList();
    }

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return as(gateway.forUser(0L, "STAFF", null), request);
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
