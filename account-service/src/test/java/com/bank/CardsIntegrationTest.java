package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.account.card.CardStatements;
import com.bank.common.security.InternalTokens;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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

    @Autowired
    private CardStatements cardStatements;

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

    @Test
    void aStatementIsMadeMonthlyAndInterestIsAddedOnlyOnWhatWasLeftUnpaid() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "500.00");
        long card = idOf(issue(customer, "1000.00").andExpect(jsonPath("$.nextStatementOn")
                        .value(YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(1).toString()))
                .andReturn().getResponse().getContentAsString());
        purchase(card, "200.00", "Shop").andExpect(status().isOk());

        cardStatements.run();
        assertThat(statementsFor(card)).isZero(); // Not due until next month.
        makeStatementDue(card, 2);
        assertThat(statementsFor(card)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_statement_on from cards where id = ?", LocalDate.class, card))
                .as("one month at a time").isEqualTo(monthsAgo(2).plusMonths(1));
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/statements", card)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].balance").value(200.00))
                .andExpect(jsonPath("$.content[0].interest").value(0.00))
                .andExpect(jsonPath("$.content[0].minimumPayment").value(25.00))
                .andExpect(jsonPath("$.content[0].periodEnd").value(monthsAgo(2).minusDays(1).toString()))
                .andExpect(jsonPath("$.content[0].dueOn").value(monthsAgo(2).plusDays(21).toString()))
                .andExpect(jsonPath("$.content[0].paidSince").value(0));

        // 50 of the 200 is paid, so the next statement adds a month's interest on the other 150.
        pay(customer, card, account, "50.00").andExpect(status().isOk());
        makeStatementDue(card, 1);
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}", card)))
                .andExpect(jsonPath("$.owed").value(152.49)); // 150 x 19.90% / 12 = 2.4875
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/statements", card)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].interest").value(2.49))
                .andExpect(jsonPath("$.content[0].balance").value(152.49))
                .andExpect(jsonPath("$.content[1].paidSince").value(50.00));
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/transactions", card)))
                .andExpect(jsonPath("$.content[0].type").value("INTEREST"))
                .andExpect(jsonPath("$.content[0].amount").value(2.49));

        // Paid in full: no interest on the next one.
        pay(customer, card, account, "152.49").andExpect(status().isOk());
        purchase(card, "30.00", "Cafe").andExpect(status().isOk());
        makeStatementDue(card, 0);
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/statements", card)))
                .andExpect(jsonPath("$.content[0].interest").value(0.00))
                .andExpect(jsonPath("$.content[0].balance").value(30.00))
                .andExpect(jsonPath("$.content[0].minimumPayment").value(25.00));
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload like ?",
                Integer.class, "card-" + card, "%\"CARD_STATEMENT\"%")).isEqualTo(3);
    }

    @Test
    void theCustomerIsWarnedOnceWhenAPurchaseTakesTheCardToEightyPercentOfItsLimit() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "1000.00");
        long card = idOf(issue(customer, "1000.00").andReturn().getResponse().getContentAsString());

        purchase(card, "799.99", "Shop").andExpect(status().isOk());
        assertThat(warningsFor(card)).isZero();
        purchase(card, "0.01", "Shop").andExpect(status().isOk());
        assertThat(warningsFor(card)).isEqualTo(1);
        purchase(card, "100.00", "Shop").andExpect(status().isOk());
        assertThat(warningsFor(card)).as("already past the line").isEqualTo(1);

        // Paid back below the line, then crossing it again warns again.
        pay(customer, card, account, "400.00").andExpect(status().isOk());
        purchase(card, "300.00", "Shop").andExpect(status().isOk());
        assertThat(warningsFor(card)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? and payload like ? "
                + "order by id desc limit 1", String.class, "card-" + card, "%CARD_LIMIT_NEARLY_REACHED%"))
                .contains("\"creditLimit\":1000.00").contains("\"amount\":800.00").doesNotContain("Shop");
    }

    private int warningsFor(long card) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and payload like ?",
                Integer.class, "card-" + card, "%\"CARD_LIMIT_NEARLY_REACHED\"%");
    }

    @Test
    void theMinimumPaymentIsFivePercentButNeverMoreThanIsOwed() throws Exception {
        long customer = newCustomerId();
        long card = idOf(issue(customer, "5000.00").andReturn().getResponse().getContentAsString());
        purchase(card, "1000.00", "Shop").andExpect(status().isOk());
        makeStatementDue(card, 0);
        mockMvc.perform(asStaff(get("/api/cards/{id}/statements", card)))
                .andExpect(jsonPath("$.content[0].minimumPayment").value(50.00));

        long small = idOf(issue(customer, "100.00").andReturn().getResponse().getContentAsString());
        purchase(small, "10.00", "Shop").andExpect(status().isOk());
        makeStatementDue(small, 0);
        mockMvc.perform(asStaff(get("/api/cards/{id}/statements", small)))
                .andExpect(jsonPath("$.content[0].minimumPayment").value(10.00));
    }

    @Test
    void aClosedCardGetsNoStatementsAndOthersCannotSeeACardsStatements() throws Exception {
        long customer = newCustomerId();
        long card = idOf(issue(customer, "100.00").andReturn().getResponse().getContentAsString());
        mockMvc.perform(asStaff(post("/api/cards/{id}/close", card))).andExpect(status().isOk());
        makeStatementDue(card, 0);
        assertThat(statementsFor(card)).isZero();

        mockMvc.perform(asCustomer(newCustomerId(), get("/api/cards/{id}/statements", card)))
                .andExpect(status().isNotFound());
    }

    /** Makes the card's statement due some months ago and runs the job once, as on the first of a month. */
    private void makeStatementDue(long card, int monthsAgo) {
        jdbc.update("update cards set next_statement_on = ? where id = ?", monthsAgo(monthsAgo), card);
        cardStatements.run();
    }

    private static LocalDate monthsAgo(int months) {
        return LocalDate.now(ZoneOffset.UTC).minusMonths(months);
    }

    private int statementsFor(long card) {
        return jdbc.queryForObject("select count(*) from card_statements where card_id = ?", Integer.class, card);
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
