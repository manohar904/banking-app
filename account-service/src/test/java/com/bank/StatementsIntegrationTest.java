package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.account.card.CardStatements;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;
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

/** Statements downloaded as PDF, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = "bank.kafka.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class StatementsIntegrationTest {

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
    private CardStatements cardStatements;

    @MockitoBean
    private CustomerServiceClient customerService;

    @Test
    void anAccountStatementShowsTheMonthsEntriesBetweenItsOpeningAndClosingBalance() throws Exception {
        long customer = newCustomerId();
        when(customerService.nameOf(customer)).thenReturn(Optional.of("Ada Lovelace"));
        long account = createAccount(customer);
        long other = createAccount(newCustomerId());
        String number = numberOf(account);
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);

        // Last month: 500 in. This month: 120 out, 30 to another account, 45.50 in.
        deposit(account, "500.00");
        moveToLastMonth(account);
        withdraw(account, "120.00");
        transfer(account, numberOf(other), "30.00");
        deposit(account, "45.50");

        String now = pdfText(mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/statement", account)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"statement-" + number + "-" + thisMonth + ".pdf\""))
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(now).contains(squashed("Account statement", "Ada Lovelace", number, "(month so far)",
                "Balance at the start 500.00", "Money in 45.50", "Money out 150.00", "Balance now 395.50",
                "Withdrawal", "Transfer to " + numberOf(other), "Deposit"));

        String last = pdfText(mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/statement", account)
                        .param("month", thisMonth.minusMonths(1).toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(last).contains(squashed("Balance at the start 0.00", "Money in 500.00", "Balance at the end 500.00"))
                .doesNotContain("Withdrawal");
    }

    @Test
    void anAccountWithNothingInTheMonthStillGetsAStatementWithoutTheNameIfItCannotBeHad() throws Exception {
        long customer = newCustomerId();
        when(customerService.nameOf(customer)).thenReturn(Optional.empty());
        long account = createAccount(customer);

        String text = pdfText(mockMvc.perform(asStaff(get("/api/accounts/{id}/statement", account)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(text).contains(squashed("No money moved in or out", "Balance now 0.00")).doesNotContain("Name");
    }

    @Test
    void onlyMonthsTheAccountWasOpenHaveStatementsAndOnlyItsOwnerAndStaffSeeThem() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        YearMonth thisMonth = YearMonth.now(ZoneOffset.UTC);

        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/statement", account)
                .param("month", thisMonth.plusMonths(1).toString()))).andExpect(status().isBadRequest());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/statement", account)
                .param("month", thisMonth.minusMonths(1).toString()))).andExpect(status().isBadRequest());
        mockMvc.perform(asCustomer(customer, get("/api/accounts/{id}/statement", account)
                .param("month", "not-a-month"))).andExpect(status().isBadRequest());
        mockMvc.perform(asCustomer(newCustomerId(), get("/api/accounts/{id}/statement", account)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/{id}/statement", account)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCardStatementAddsUpFromThePreviousBalanceToTheNewOne() throws Exception {
        long customer = newCustomerId();
        when(customerService.nameOf(customer)).thenReturn(Optional.of("Ada Lovelace"));
        long account = createAccount(customer);
        deposit(account, "500.00");
        long card = idOf(mockMvc.perform(asStaff(post("/api/cards")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"creditLimit\": 1000.00}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String lastFour = jdbc.queryForObject("select last_four from cards where id = ?", String.class, card);

        purchase(card, "200.00", "Corner Shop");
        makeStatementDue(card, 2);
        mockMvc.perform(asCustomer(customer, post("/api/cards/{id}/payments", card))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\": " + account + ", \"amount\": 50.00}")).andExpect(status().isOk());
        purchase(card, "10.00", "Cafe");
        makeStatementDue(card, 1);

        long first = statementId(card, 1);
        long second = statementId(card, 0);
        String firstText = pdfText(cardPdf(customer, card, first));
        assertThat(firstText).contains(squashed("Credit card statement", "Card **** **** **** " + lastFour, "Corner Shop",
                "Owed at the last statement 0.00", "Purchases 200.00", "Owed now 200.00",
                "Minimum payment 25.00")).doesNotContain("Cafe");

        // 150 left unpaid of the first statement: a month's interest of 2.49 at 19.90%.
        String secondText = pdfText(cardPdf(customer, card, second));
        assertThat(secondText).contains(squashed("Owed at the last statement 200.00", "Purchases 10.00", "Interest 2.49",
                "Payments 50.00", "Owed now 162.49", "Payment from account ending " + numberOf(account).substring(8),
                "Cafe")).doesNotContain(squashed("Corner Shop"));

        mockMvc.perform(asCustomer(newCustomerId(), get("/api/cards/{id}/statements/{s}/pdf", card, first)))
                .andExpect(status().isNotFound());
        long otherCard = idOf(mockMvc.perform(asStaff(post("/api/cards")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"creditLimit\": 100.00}"))
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/statements/{s}/pdf", otherCard, first)))
                .andExpect(status().isNotFound());
    }

    /** What to look for in pdfText, without spaces too. */
    private static String[] squashed(String... parts) {
        return Arrays.stream(parts).map(part -> part.replaceAll("\\s+", "")).toArray(String[]::new);
    }

    private byte[] cardPdf(long customer, long card, long statement) throws Exception {
        return mockMvc.perform(asCustomer(customer, get("/api/cards/{id}/statements/{s}/pdf", card, statement)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andReturn().getResponse().getContentAsByteArray();
    }

    /** The PDF's text without any spaces or line breaks, as table cells come out run together. */
    private static String pdfText(byte[] pdf) throws Exception {
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PdfReader reader = new PdfReader(pdf)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append(' ');
            }
            return text.toString().replaceAll("\\s+", "");
        }
    }

    /** Dates the account's entries so far to the middle of last month, as if made then. */
    private void moveToLastMonth(long account) {
        Instant middle = YearMonth.now(ZoneOffset.UTC).minusMonths(1).atDay(15).atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        jdbc.update("update accounts set created_at = ? where id = ?",
                java.sql.Timestamp.from(middle.minus(1, ChronoUnit.DAYS)), account);
        jdbc.update("update account_transactions set created_at = ? where account_id = ?",
                java.sql.Timestamp.from(middle), account);
    }

    private void makeStatementDue(long card, int monthsAgo) {
        jdbc.update("update cards set next_statement_on = ? where id = ?",
                LocalDate.now(ZoneOffset.UTC).minusMonths(monthsAgo), card);
        cardStatements.run();
    }

    /** One of the card's statements: 0 is the newest, 1 the one before. */
    private long statementId(long card, int back) {
        return jdbc.queryForObject("select id from card_statements where card_id = ? order by id desc offset ? limit 1",
                Long.class, card, back);
    }

    private void purchase(long card, String amount, String merchant) throws Exception {
        mockMvc.perform(asStaff(post("/api/cards/{id}/purchases", card)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + ", \"merchant\": \"" + merchant + "\"}")).andExpect(status().isOk());
    }

    private long createAccount(long customer) throws Exception {
        return idOf(mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void deposit(long account, String amount) throws Exception {
        money(account, "deposit", amount);
    }

    private void withdraw(long account, String amount) throws Exception {
        money(account, "withdraw", amount);
    }

    private void money(long account, String operation, String amount) throws Exception {
        mockMvc.perform(asStaff(post("/api/accounts/{id}/" + operation, account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}")).andExpect(status().isOk());
    }

    private void transfer(long account, String toNumber, String amount) throws Exception {
        mockMvc.perform(asStaff(post("/api/accounts/{id}/transfer", account)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toAccountNumber\": \"" + toNumber + "\", \"amount\": " + amount + "}"))
                .andExpect(status().isOk());
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
