package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Loans, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = {"bank.kafka.enabled=false", "bank.loans.annual-interest-rate=9.50"})
@AutoConfigureMockMvc
@Testcontainers
class LoansIntegrationTest {

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
    void aQuoteShowsTheMonthlyPaymentAndTheTotalCost() throws Exception {
        long customer = newCustomerId();
        mockMvc.perform(asCustomer(customer, get("/api/loans/quote").param("amount", "1000.00").param("months", "12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.annualRate").value(9.50))
                .andExpect(jsonPath("$.monthlyPayment").value(87.69))
                .andExpect(jsonPath("$.totalInterest").value(52.21));
        mockMvc.perform(asCustomer(customer, get("/api/loans/quote").param("amount", "10.00").param("months", "12")))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(asCustomer(customer, get("/api/loans/offer")))
                .andExpect(jsonPath("$.maxMonths").value(60));
    }

    @Test
    void aLoanIsAskedForApprovedPaidInAndRepaid() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        long loan = apply(customer, account, "1000.00", 12).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.monthlyPayment").value(87.69))
                .andExpect(jsonPath("$.accountNumber").value(numberOf(account)))
                .andReturn().getResponse().getContentAsString().transform(LoansIntegrationTest::idOf);

        mockMvc.perform(asStaff(get("/api/loans").param("status", "REQUESTED")))
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(loan)).isNotEmpty());

        mockMvc.perform(asStaff(post("/api/loans/{id}/approve", loan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.nextPaymentAmount").value(87.69))
                .andExpect(jsonPath("$.overdue").value(false));
        assertThat(balance(account)).isEqualByComparingTo("1000.00");

        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/repay", loan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentsMade").value(1))
                // 7.92 of the 87.69 was interest.
                .andExpect(jsonPath("$.remainingPrincipal").value(920.23))
                .andExpect(jsonPath("$.repayments[0].interest").value(7.92));
        assertThat(balance(account)).isEqualByComparingTo("912.31");

        // Paying off early costs what is still owed, with no further interest.
        deposit(account, "100.00");
        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/pay-off", loan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID_OFF"))
                .andExpect(jsonPath("$.remainingPrincipal").value(0))
                .andExpect(jsonPath("$.nextPaymentDue").doesNotExist());
        assertThat(balance(account)).isEqualByComparingTo("92.08");
    }

    @Test
    void repayingOnScheduleClearsTheLoan() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        deposit(account, "100.00"); // covers the interest
        long loan = approvedLoan(customer, account, "1000.00", 12);

        for (int month = 1; month <= 12; month++) {
            mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/repay", loan))).andExpect(status().isOk());
        }
        mockMvc.perform(asCustomer(customer, get("/api/loans/{id}", loan)))
                .andExpect(jsonPath("$.status").value("PAID_OFF"))
                .andExpect(jsonPath("$.repayments.length()").value(12));
        assertThat(jdbc.queryForObject("select sum(principal) from loan_repayments where loan_id = ?",
                BigDecimal.class, loan)).isEqualByComparingTo("1000.00");
        // 1000 paid in, 100 deposited, 1052.21 repaid.
        assertThat(balance(account)).isEqualByComparingTo("47.79");
        assertThat(ledgerTypes(account)).containsExactly("LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT",
                "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT",
                "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_REPAYMENT", "LOAN_PAYOUT", "DEPOSIT");
        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/repay", loan))).andExpect(status().isConflict());
    }

    @Test
    void aRepaymentNeedsEnoughMoneyAndChangesNothingOtherwise() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        long loan = approvedLoan(customer, account, "1000.00", 12);
        mockMvc.perform(asStaff(post("/api/accounts/{id}/withdraw", account))
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 950.00}")).andExpect(status().isOk());

        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/repay", loan)))
                .andExpect(status().isUnprocessableContent());

        mockMvc.perform(asCustomer(customer, get("/api/loans/{id}", loan)))
                .andExpect(jsonPath("$.paymentsMade").value(0))
                .andExpect(jsonPath("$.remainingPrincipal").value(1000.00));
        assertThat(balance(account)).isEqualByComparingTo("50.00");
    }

    @Test
    void oneRequestWaitsAtATimeAndOnlyWithinTheLimits() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        apply(customer, account, "50.00", 12).andExpect(status().isUnprocessableContent());
        apply(customer, account, "1000.00", 61).andExpect(status().isUnprocessableContent());
        apply(customer, account, "1000.00", 12).andExpect(status().isCreated());
        apply(customer, account, "2000.00", 24).andExpect(status().isConflict());
    }

    @Test
    void customersKeepToTheirOwnLoansAndStaffDecide() throws Exception {
        long owner = newCustomerId();
        long other = newCustomerId();
        long ownerAccount = createAccount(owner);
        long otherAccount = createAccount(other);

        // Someone else's account can't be named, and staff don't ask for loans.
        apply(owner, otherAccount, "1000.00", 12).andExpect(status().isNotFound());
        mockMvc.perform(asStaff(post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                .content(applicationJson(ownerAccount, "1000.00", 12))).andExpect(status().isForbidden());

        long loan = idOf(apply(owner, ownerAccount, "1000.00", 12).andReturn().getResponse().getContentAsString());
        mockMvc.perform(asCustomer(other, get("/api/loans/{id}", loan))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, post("/api/loans/{id}/cancel", loan))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, get("/api/loans").param("customerId", String.valueOf(owner))))
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(loan)).isEmpty());
        mockMvc.perform(asCustomer(owner, post("/api/loans/{id}/approve", loan))).andExpect(status().isForbidden());

        mockMvc.perform(asStaff(get("/api/loans").param("customerId", String.valueOf(owner))))
                .andExpect(jsonPath("$.content[0].id").value(loan));
    }

    @Test
    void staffCanRejectWithAReasonAndCustomersCanCancel() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        long rejected = idOf(apply(customer, account, "1000.00", 12).andReturn().getResponse().getContentAsString());
        mockMvc.perform(asStaff(post("/api/loans/{id}/reject", rejected)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"Income too low for this amount.\"}"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(asCustomer(customer, get("/api/loans/{id}", rejected)))
                .andExpect(jsonPath("$.decisionNote").value("Income too low for this amount."));
        mockMvc.perform(asStaff(post("/api/loans/{id}/approve", rejected))).andExpect(status().isConflict());
        assertThat(balance(account)).isEqualByComparingTo("0.00");

        long cancelled = idOf(apply(customer, account, "500.00", 6).andReturn().getResponse().getContentAsString());
        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/cancel", cancelled)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForList("select action from audit_log where target_id in (?, ?) order by id",
                String.class, "loan-" + rejected, "loan-" + cancelled))
                .containsExactly("LOAN_REQUESTED", "LOAN_REJECTED", "LOAN_REQUESTED", "LOAN_CANCELLED");
    }

    @Test
    void anAccountWithALoanWaitingCannotBeDeleted() throws Exception {
        long customer = newCustomerId();
        long account = createAccount(customer);
        long loan = idOf(apply(customer, account, "1000.00", 12).andReturn().getResponse().getContentAsString());

        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isConflict());

        mockMvc.perform(asCustomer(customer, post("/api/loans/{id}/cancel", loan))).andExpect(status().isOk());
        mockMvc.perform(asStaff(delete("/api/accounts/{id}", account))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from loans where id = ?", Long.class, loan)).isZero();
    }

    private long approvedLoan(long customer, long account, String amount, int months) throws Exception {
        long loan = idOf(apply(customer, account, amount, months).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        mockMvc.perform(asStaff(post("/api/loans/{id}/approve", loan))).andExpect(status().isOk());
        return loan;
    }

    private ResultActions apply(long customer, long account, String amount, int months) throws Exception {
        return mockMvc.perform(asCustomer(customer, post("/api/loans")).contentType(MediaType.APPLICATION_JSON)
                .content(applicationJson(account, amount, months)));
    }

    private static String applicationJson(long account, String amount, int months) {
        return "{\"accountId\": %d, \"amount\": %s, \"months\": %d, \"purpose\": \"A car\"}"
                .formatted(account, amount, months);
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

    /** Newest first. */
    private List<String> ledgerTypes(long account) {
        return jdbc.queryForList("select type from account_transactions where account_id = ? order by id desc",
                String.class, account);
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
