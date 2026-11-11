package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.security.InternalTokens;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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

/** Credit cards, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = "bank.kafka.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class CardsIntegrationTest {

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
    private InternalTokens tokens;

    @MockitoBean
    private CustomerServiceClient customerService;

    @Test
    void purchasesAddToWhatIsOwedAndPaymentsComeFromAnAccount() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "500.00");
        long card = issue(customer, "1000.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lastFour").value(org.hamcrest.Matchers.matchesPattern("\\d{4}")))
                .andExpect(jsonPath("$.available").value(1000.00))
                .andReturn().getResponse().getContentAsString().transform(CardsIntegrationTest::idOf);

        purchase(card, "120.50", "Corner Shop").andExpect(status().isOk())
                .andExpect(jsonPath("$.owed").value(120.50))
                .andExpect(jsonPath("$.available").value(879.50));
        purchase(card, "79.50", "Bookshop").andExpect(jsonPath("$.owed").value(200.00));

        pay(customer, card, account, "150.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.owed").value(50.00));
        assertThat(balance(account)).isEqualByComparingTo("350.00");
        assertThat(jdbc.queryForObject("select type from account_transactions where account_id = ? order by id desc limit 1",
                String.class, account)).isEqualTo("CARD_PAYMENT");

        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/transactions", card)))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].type").value("PAYMENT"))
                .andExpect(jsonPath("$.content[0].accountNumber").value(numberOf(account)))
                .andExpect(jsonPath("$.content[1].merchant").value("Bookshop"))
                .andExpect(jsonPath("$.content[2].owedAfter").value(120.50));
        mockMvc.perform(asCustomer(customer, get("/api/cards")))
                .andExpect(jsonPath("$[0].id").value(card));
    }

    @Test
    void aPurchaseCannotGoOverTheLimitNorAPaymentOverWhatIsOwed() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "500.00");
        long card = idOf(issue(customer, "100.00").andReturn().getResponse().getContentAsString());

        purchase(card, "100.01", "Too much").andExpect(status().isUnprocessableContent());
        purchase(card, "100.00", "Exactly").andExpect(status().isOk());
        pay(customer, card, account, "100.01").andExpect(status().isUnprocessableContent());
        assertThat(balance(account)).isEqualByComparingTo("500.00");
    }

    @Test
    void notEnoughMoneyInTheAccountChangesNothing() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "10.00");
        long card = idOf(issue(customer, "1000.00").andReturn().getResponse().getContentAsString());
        purchase(card, "50.00", "Shop").andExpect(status().isOk());

        pay(customer, card, account, "50.00").andExpect(status().isUnprocessableContent());

        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}", card))).andExpect(jsonPath("$.owed").value(50.00));
        assertThat(balance(account)).isEqualByComparingTo("10.00");
    }

    @Test
    void aFrozenCardTakesNoPurchasesButCanStillBePaid() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "100.00");
        long card = idOf(issue(customer, "1000.00").andReturn().getResponse().getContentAsString());
        purchase(card, "40.00", "Shop").andExpect(status().isOk());

        mockMvc.perform(asCustomer(customer, post("/api/cards/{id}/freeze", card)))
                .andExpect(jsonPath("$.status").value("FROZEN"));
        purchase(card, "1.00", "Shop").andExpect(status().isConflict());
        pay(customer, card, account, "40.00").andExpect(status().isOk());

        mockMvc.perform(asCustomer(customer, post("/api/cards/{id}/unfreeze", card)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        purchase(card, "1.00", "Shop").andExpect(status().isOk());
    }

    @Test
    void staffChangeTheLimitAndCloseACardOnceNothingIsOwed() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "100.00");
        long card = idOf(issue(customer, "1000.00").andReturn().getResponse().getContentAsString());
        purchase(card, "300.00", "Shop").andExpect(status().isOk());

        changeLimit(card, "200.00").andExpect(status().isConflict());
        changeLimit(card, "20000.01").andExpect(status().isUnprocessableContent());
        changeLimit(card, "500.00").andExpect(jsonPath("$.available").value(200.00));

        mockMvc.perform(asStaff(post("/api/cards/{id}/close", card))).andExpect(status().isConflict());
        deposit(account, "200.00");
        pay(customer, card, account, "300.00").andExpect(status().isOk());
        mockMvc.perform(asStaff(post("/api/cards/{id}/close", card)))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        purchase(card, "1.00", "Shop").andExpect(status().isConflict());
        assertThat(jdbc.queryForList("select action from audit_log where target_id = ? order by id", String.class,
                "card-" + card)).containsExactly("CARD_ISSUED", "CARD_PURCHASE", "CARD_LIMIT_CHANGED", "CARD_PAID",
                "CARD_CLOSED");
    }

    @Test
    void customersKeepToTheirOwnCardsAndOnlyStaffIssueAndBuy() throws Exception {
        long owner = newCustomerId();
        long other = newCustomerId();
        long ownerAccount = createAccount(owner);
        long otherAccount = createAccount(other);
        deposit(otherAccount, "100.00");
        long card = idOf(issue(owner, "1000.00").andReturn().getResponse().getContentAsString());
        purchase(card, "10.00", "Shop").andExpect(status().isOk());

        mockMvc.perform(asCustomer(other, get("/api/cards/{id}", card))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, post("/api/cards/{id}/freeze", card))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, get("/api/cards").param("customerId", String.valueOf(owner))))
                .andExpect(jsonPath("$.length()").value(0));
        // The owner can't pay from someone else's account either.
        pay(owner, card, otherAccount, "10.00").andExpect(status().isNotFound());

        mockMvc.perform(asCustomer(owner, post("/api/cards")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": " + owner + ", \"creditLimit\": 100}")).andExpect(status().isForbidden());
        mockMvc.perform(asCustomer(owner, post("/api/cards/{id}/purchases", card)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 1, \"merchant\": \"Me\"}")).andExpect(status().isForbidden());
        assertThat(ownerAccount).isPositive();
    }

    @Test
    void aCustomerWithAnOpenCardCannotBeDeleted() throws Exception {
        long customer = newCustomerId();
        long card = idOf(issue(customer, "1000.00").andReturn().getResponse().getContentAsString());

        mockMvc.perform(as(tokens.forThisService(), put("/internal/retired-customers/{id}", customer)))
                .andExpect(status().isConflict());

        mockMvc.perform(asStaff(post("/api/cards/{id}/close", card))).andExpect(status().isOk());
        mockMvc.perform(as(tokens.forThisService(), put("/internal/retired-customers/{id}", customer)))
                .andExpect(status().is2xxSuccessful());
        // And a retired customer gets no new cards.
        issue(customer, "100.00").andExpect(status().isNotFound());
    }

    @Test
    void aCustomerMayHaveThreeOpenCards() throws Exception {
        long customer = newCustomerId();
        for (int i = 0; i < 3; i++) {
            issue(customer, "100.00").andExpect(status().isCreated());
        }
        issue(customer, "100.00").andExpect(status().isConflict());
    }

    private ResultActions issue(long customer, String limit) throws Exception {
        return mockMvc.perform(asStaff(post("/api/cards")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\": " + customer + ", \"creditLimit\": " + limit + "}"));
    }

    private ResultActions purchase(long card, String amount, String merchant) throws Exception {
        return mockMvc.perform(asStaff(post("/api/cards/{id}/purchases", card)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + ", \"merchant\": \"" + merchant + "\"}"));
    }

    private ResultActions pay(long customer, long card, long account, String amount) throws Exception {
        return mockMvc.perform(asCustomer(customer, post("/api/cards/{id}/payments", card))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\": " + account + ", \"amount\": " + amount + "}"));
    }

    private ResultActions changeLimit(long card, String limit) throws Exception {
        return mockMvc.perform(asStaff(put("/api/cards/{id}/limit", card)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"creditLimit\": " + limit + "}"));
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
