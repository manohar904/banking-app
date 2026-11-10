package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.events.OutboxRelay;
import com.bank.common.security.InternalTokens;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** What must hold between a change and its event. e2e-tests covers delivery through a real Kafka. */
@SpringBootTest(properties = {
        // The background jobs stay out of the way: these tests run the relay themselves.
        "bank.events.relay-delay=3600000",
        "bank.health.heartbeat-interval=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountEventsIntegrationTest {

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

    @Autowired
    private OutboxRelay relay;

    @MockitoBean
    private CustomerServiceClient customerService;

    @MockitoBean
    private KafkaTemplate<String, String> kafka;

    @BeforeEach
    void emptyOutbox() {
        jdbc.update("delete from outbox_events");
        reset(kafka);
    }

    @Test
    void depositRecordsAnEventWithTheExactAmounts() throws Exception {
        long account = createAccount();

        move(account, "deposit", "100.10").andExpect(status().isOk());

        List<String> events = eventsFor(account);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).contains("ACCOUNT_OPENED");
        assertThat(events.get(1)).contains("MONEY_DEPOSITED", "\"amount\":100.10", "\"balanceAfter\":100.10");
    }

    @Test
    void moneyEventsSayWhoToTellWithoutTheFullAccountNumber() throws Exception {
        long account = createAccount();
        String number = jdbc.queryForObject("select account_number from accounts where id = ?", String.class, account);
        long customer = jdbc.queryForObject("select customer_id from accounts where id = ?", Long.class, account);

        move(account, "deposit", "1.00").andExpect(status().isOk());

        assertThat(eventsFor(account).get(1))
                .contains("\"customerId\":" + customer, "\"accountEnding\":\"" + number.substring(8) + "\"")
                .doesNotContain(number);
    }

    @Test
    void refusedWithdrawalRecordsNoEvent() throws Exception {
        long account = createAccount();

        move(account, "withdraw", "1.00").andExpect(status().isUnprocessableContent());

        assertThat(eventsFor(account)).hasSize(1); // only ACCOUNT_OPENED
    }

    @Test
    void depositThatRollsBackRecordsNoEvent() throws Exception {
        long account = createAccount();
        String largestAmount = "99999999999999999.99";
        move(account, "deposit", largestAmount).andExpect(status().isOk());

        move(account, "deposit", largestAmount).andExpect(status().is4xxClientError());

        assertThat(eventsFor(account)).hasSize(2); // ACCOUNT_OPENED and the first deposit
    }

    @Test
    void eventIsRemovedOnlyOnceKafkaHasConfirmedIt() throws Exception {
        long account = createAccount();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        relay.publishPending();

        verify(kafka).send(eq(OutboxRelay.TOPIC), eq(String.valueOf(account)), any());
        assertThat(eventsFor(account)).isEmpty();
    }

    @Test
    void bankingKeepsWorkingWhileKafkaIsDownAndEventsAreDeliveredInOrderLater() throws Exception {
        long account = createAccount();
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("Kafka is down"));
        relay.publishPending();

        // The customer's money moves regardless, and nothing is lost.
        move(account, "deposit", "5.00").andExpect(status().isOk());
        relay.publishPending();
        assertThat(eventsFor(account)).hasSize(2);

        reset(kafka);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        relay.publishPending();

        InOrder order = inOrder(kafka);
        order.verify(kafka).send(eq(OutboxRelay.TOPIC), eq(String.valueOf(account)), org.mockito.ArgumentMatchers.contains("ACCOUNT_OPENED"));
        order.verify(kafka).send(eq(OutboxRelay.TOPIC), eq(String.valueOf(account)), org.mockito.ArgumentMatchers.contains("MONEY_DEPOSITED"));
        assertThat(eventsFor(account)).isEmpty();
    }

    @Test
    void laterEventsWaitBehindOneThatCouldNotBeSent() throws Exception {
        long account = createAccount();
        move(account, "deposit", "5.00").andExpect(status().isOk());
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("Kafka is down")));

        relay.publishPending();

        verify(kafka).send(anyString(), anyString(), org.mockito.ArgumentMatchers.contains("ACCOUNT_OPENED"));
        verify(kafka, never()).send(anyString(), anyString(), org.mockito.ArgumentMatchers.contains("MONEY_DEPOSITED"));
        assertThat(eventsFor(account)).hasSize(2);
        clearInvocations(kafka);
    }

    private List<String> eventsFor(long account) {
        return jdbc.queryForList("select payload from outbox_events where aggregate_id = ? order by id",
                String.class, String.valueOf(account));
    }

    private String staffToken() {
        return "Bearer " + gateway.forUser(0L, "STAFF", null);
    }

    private long createAccount() throws Exception {
        long customer = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String response = mockMvc.perform(post("/api/accounts")
                        .header(HttpHeaders.AUTHORIZATION, staffToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(response, "$.id");
        return id.longValue();
    }

    private ResultActions move(long account, String operation, String amount) throws Exception {
        return mockMvc.perform(post("/api/accounts/" + account + "/" + operation)
                .header(HttpHeaders.AUTHORIZATION, staffToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }
}
