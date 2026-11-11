package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.common.security.TestGateway;
import com.bank.customer.AccountServiceClient;
import com.bank.customer.AuthServiceClient;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Help questions, against real PostgreSQL and the real security rules. Requires Docker. */
@SpringBootTest(properties = "bank.kafka.enabled=false")
@AutoConfigureMockMvc
@Testcontainers
class HelpRequestsIntegrationTest {

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
    private AccountServiceClient accountService;

    @MockitoBean
    private AuthServiceClient authService;

    @MockitoBean
    private JavaMailSender mail;

    @Test
    void aCustomerAsksAndStaffAnswer() throws Exception {
        long customer = createCustomer("Ada", "Lovelace");
        long question = ask(customer, "Card not arrived", "I ordered a card two weeks ago.");

        mockMvc.perform(asCustomer(customer, get("/api/help-requests")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(question))
                .andExpect(jsonPath("$.content[0].status").value("WAITING_FOR_BANK"))
                // A customer knows who they are; only staff get the name.
                .andExpect(jsonPath("$.content[0].customerName").doesNotExist());
        mockMvc.perform(asStaff(get("/api/help-requests").param("status", "WAITING_FOR_BANK")))
                .andExpect(jsonPath("$.content[?(@.id == %d)].customerName".formatted(question)).value("Ada Lovelace"));

        mockMvc.perform(asStaff(post("/api/help-requests/{id}/messages", question))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"It was posted yesterday.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[1].fromBank").value(true));

        mockMvc.perform(asCustomer(customer, post("/api/help-requests/{id}/messages", question))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"Thanks!\"}"))
                .andExpect(jsonPath("$.status").value("WAITING_FOR_BANK"));

        mockMvc.perform(asCustomer(customer, post("/api/help-requests/{id}/close", question)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(asStaff(post("/api/help-requests/{id}/messages", question))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"One more thing\"}"))
                .andExpect(status().isConflict());

        // Ids only in the audit log (and so in events), never what was written.
        assertThat(jdbc.queryForList("select details from audit_log where target_id = ? and action like 'HELP%'",
                String.class, String.valueOf(question)))
                .hasSize(4)
                .noneMatch(details -> details.contains("card") || details.contains("Thanks"));
    }

    @Test
    void aCustomerCannotSeeSomeoneElsesQuestion() throws Exception {
        long owner = createCustomer("Grace", "Hopper");
        long other = createCustomer("Alan", "Turing");
        long question = ask(owner, "Private", "Only for me");

        mockMvc.perform(asCustomer(other, get("/api/help-requests/{id}", question))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, post("/api/help-requests/{id}/messages", question))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"Hi\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, post("/api/help-requests/{id}/close", question))).andExpect(status().isNotFound());
        mockMvc.perform(asCustomer(other, get("/api/help-requests")))
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(question)).isEmpty());
        mockMvc.perform(asStaff(get("/api/help-requests/{id}", question)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].body").value("Only for me"));
    }

    @Test
    void onlyCustomersAsk() throws Exception {
        mockMvc.perform(asStaff(post("/api/help-requests"))
                        .contentType(MediaType.APPLICATION_JSON).content(questionJson("Hello", "World")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/help-requests")
                        .contentType(MediaType.APPLICATION_JSON).content(questionJson("Hello", "World")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void questionsMustHaveASubjectAndAShortEnoughMessage() throws Exception {
        long customer = createCustomer("Edsger", "Dijkstra");
        mockMvc.perform(asCustomer(customer, post("/api/help-requests"))
                        .contentType(MediaType.APPLICATION_JSON).content(questionJson(" ", "Text")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.subject").exists());
        mockMvc.perform(asCustomer(customer, post("/api/help-requests"))
                        .contentType(MediaType.APPLICATION_JSON).content(questionJson("Long", "x".repeat(2001))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.message").exists());
    }

    @Test
    void aCustomerMayHaveFiveOpenQuestionsAtATime() throws Exception {
        long customer = createCustomer("Barbara", "Liskov");
        long first = ask(customer, "Question 1", "Text");
        for (int i = 2; i <= 5; i++) {
            ask(customer, "Question " + i, "Text");
        }
        postQuestion(customer, "Question 6", "Text")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("5 open questions")));

        mockMvc.perform(asCustomer(customer, post("/api/help-requests/{id}/close", first))).andExpect(status().isOk());
        postQuestion(customer, "Question 6", "Text").andExpect(status().isCreated());
    }

    @Test
    void deletingACustomerDeletesTheirQuestions() throws Exception {
        long customer = createCustomer("Ken", "Thompson");
        long question = ask(customer, "Bye", "Closing my account");

        jdbc.update("delete from customers where id = ?", customer);

        assertThat(jdbc.queryForObject("select count(*) from help_requests where id = ?", Long.class, question)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from help_messages where request_id = ?", Long.class, question))
                .isZero();
    }

    private long ask(long customer, String subject, String message) throws Exception {
        String body = postQuestion(customer, subject, message)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_FOR_BANK"))
                .andExpect(jsonPath("$.messages[0].fromBank").value(false))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions postQuestion(long customer, String subject, String message) throws Exception {
        return mockMvc.perform(asCustomer(customer, post("/api/help-requests"))
                .contentType(MediaType.APPLICATION_JSON).content(questionJson(subject, message)));
    }

    private static String questionJson(String subject, String message) {
        return "{\"subject\": \"" + subject + "\", \"message\": \"" + message + "\"}";
    }

    private long createCustomer(String firstName, String lastName) throws Exception {
        String body = mockMvc.perform(asStaff(post("/api/customers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\": \"%s\", \"lastName\": \"%s\", \"email\": \"%s@example.com\"}"
                                .formatted(firstName, lastName, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private MockHttpServletRequestBuilder asStaff(MockHttpServletRequestBuilder request) {
        return as(gateway.forUser(1L, "STAFF", null), request);
    }

    private MockHttpServletRequestBuilder asCustomer(long customerId, MockHttpServletRequestBuilder request) {
        return as(gateway.forUser(100L + customerId, "CUSTOMER", customerId), request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
