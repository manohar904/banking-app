package com.bank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.CustomerServiceClient;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
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

/** Saved payees, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = {"bank.kafka.enabled=false", "bank.savings.cron=-", "bank.scheduled-payments.cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class PayeesIntegrationTest {

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
    void aCustomerSavesPayeesByNameAndRemovesThem() throws Exception {
        long customer = newCustomerId();
        String landlord = numberOf(createAccount(newCustomerId()));
        String mom = numberOf(createAccount(newCustomerId()));

        long id = idOf(add(customer, "  Landlord ", landlord).andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Landlord"))
                .andExpect(jsonPath("$.customerId").value(customer))
                .andReturn().getResponse().getContentAsString());
        add(customer, "Mom", mom).andExpect(status().isCreated());

        mockMvc.perform(asCustomer(customer, get("/api/payees")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Landlord"))
                .andExpect(jsonPath("$[1].name").value("Mom"))
                .andExpect(jsonPath("$[1].accountNumber").value(mom));

        mockMvc.perform(asCustomer(customer, delete("/api/payees/{id}", id))).andExpect(status().isNoContent());
        mockMvc.perform(asCustomer(customer, get("/api/payees"))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyRealAccountsAndNoDuplicates() throws Exception {
        long customer = newCustomerId();
        String number = numberOf(createAccount(newCustomerId()));
        String other = numberOf(createAccount(newCustomerId()));

        add(customer, "Nobody", "000000000000").andExpect(status().isNotFound());
        add(customer, "Short", "12345").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumber").exists());
        add(customer, " ", number).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.name").exists());
        add(customer, "x".repeat(41), number).andExpect(status().isBadRequest());

        add(customer, "Landlord", number).andExpect(status().isCreated());
        add(customer, "LANDLORD", other).andExpect(status().isConflict()); // same name, any case
        add(customer, "Rent", number).andExpect(status().isConflict());
        // Someone else may save the same account under the same name.
        add(newCustomerId(), "Landlord", number).andExpect(status().isCreated());
    }

    @Test
    void customersKeepToTheirOwnAndStaffCanHelp() throws Exception {
        long customer = newCustomerId();
        String number = numberOf(createAccount(newCustomerId()));
        long id = idOf(mockMvc.perform(asStaff(post("/api/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"name\": \"Landlord\", \"accountNumber\": \""
                                + number + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        long stranger = newCustomerId();
        mockMvc.perform(asCustomer(stranger, get("/api/payees")).param("customerId", String.valueOf(customer)))
                .andExpect(jsonPath("$.length()").value(0));
        // A customer's customerId in the body is ignored: it is saved as their own.
        mockMvc.perform(asCustomer(stranger, post("/api/payees")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"name\": \"Mine\", \"accountNumber\": \""
                                + number + "\"}"))
                .andExpect(jsonPath("$.customerId").value(stranger));
        mockMvc.perform(asCustomer(stranger, delete("/api/payees/{id}", id))).andExpect(status().isNotFound());
        mockMvc.perform(asStaff(get("/api/payees")).param("customerId", String.valueOf(customer)))
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(asStaff(get("/api/payees"))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/payees")).andExpect(status().isUnauthorized());
    }

    private ResultActions add(long customer, String name, String number) throws Exception {
        return mockMvc.perform(asCustomer(customer, post("/api/payees")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\", \"accountNumber\": \"" + number + "\"}"));
    }

    private long createAccount(long customer) throws Exception {
        return idOf(mockMvc.perform(asStaff(post("/api/accounts")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
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
