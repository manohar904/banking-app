package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.auth.CustomerServiceClient;
import com.bank.common.security.InternalTokens;
import com.bank.common.security.TestGateway;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Two-step sign-in, against real PostgreSQL. Emails are captured instead of sent. Requires Docker. */
@SpringBootTest(properties = {
        "bank.kafka.enabled=false",
        "bank.password-reset.from=bank@example.com",
        "bank.password-reset.base-url=https://bank.example"
})
@AutoConfigureMockMvc
@Testcontainers
class TwoStepSignInIntegrationTest {

    private static final String PASSWORD = "customer-password";

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

    @MockitoBean
    private JavaMailSender mail;

    @Test
    void turningItOnTakesACodeFromTheEmailAndThenSigningInDoesToo() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        mockMvc.perform(asCustomer(email, customer, get("/api/auth/two-step")))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.available").value(true));
        authenticate(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.userId").isNumber());

        turnOn(email, customer);

        // Now a right password sends a code instead of signing in.
        String answer = authenticate(email, PASSWORD).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.sentTo").value(org.hamcrest.Matchers.matchesPattern("[^*]\\*+@example\\.com")))
                .andReturn().getResponse().getContentAsString();
        String codeId = JsonPath.read(answer, "$.codeId");
        String code = lastCodeTo(email);

        checkCode(codeId, wrong(code)).andExpect(status().isUnauthorized());
        checkCode(codeId, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").value(customer));
        checkCode(codeId, code).andExpect(status().isGone()); // works once
    }

    @Test
    void fiveWrongCodesThrowTheCodeAway() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);
        String codeId = JsonPath.read(authenticate(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.codeId");
        String code = lastCodeTo(email);

        for (int i = 1; i < 5; i++) {
            checkCode(codeId, wrong(code)).andExpect(status().isUnauthorized());
        }
        checkCode(codeId, wrong(code)).andExpect(status().isGone());
        checkCode(codeId, code).andExpect(status().isGone()); // even the right one, now
    }

    @Test
    void untilTheCodeIsEnteredEachPasswordStillCountsTowardsTheLimit() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);

        for (int i = 0; i < 5; i++) {
            authenticate(email, PASSWORD).andExpect(status().isAccepted());
        }
        authenticate(email, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void anEarlierCodeStopsWorkingWhenANewOneIsSent() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);
        String first = JsonPath.read(authenticate(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.codeId");
        String firstCode = lastCodeTo(email);
        authenticate(email, PASSWORD).andExpect(status().isAccepted());

        checkCode(first, firstCode).andExpect(status().isGone());
    }

    @Test
    void turningItOffTakesThePassword() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);

        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/turn-off"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\": \"wrong-password\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/turn-off"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        authenticate(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aWrongCodeWhenTurningItOnIsNotMistakenForBeingSignedOut() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/start"))).andExpect(status().isAccepted());

        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"" + wrong(lastCodeTo(email)) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(twoStepOn(email)).isFalse();
    }

    @Test
    void staffSettingThePasswordOrChangingTheEmailTurnsItOff() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);
        setLogin(customer, PASSWORD).andExpect(status().isNoContent());
        assertThat(twoStepOn(email)).as("staff let the customer back in").isFalse();

        turnOn(email, customer);
        String newEmail = uniqueEmail();
        mockMvc.perform(as(tokens.forThisService(), put("/internal/logins/{id}/email", customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"" + newEmail + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(twoStepOn(newEmail)).as("codes would go to an unproven address").isFalse();
    }

    @Test
    void whenTheCodeCannotBeSentNothingIsTurnedOnAndSignInSaysSo() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        turnOn(email, customer);
        try {
            doThrow(new MailSendException("down")).when(mail).send(any(SimpleMailMessage.class));
            authenticate(email, PASSWORD).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("select count(*) from sign_in_codes c join users u on u.id = c.user_id "
                    + "where u.email = ?", Integer.class, email)).as("no code that never arrived").isZero();
        } finally {
            reset(mail);
        }
    }

    private void turnOn(String email, long customer) throws Exception {
        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/start"))).andExpect(status().isAccepted());
        mockMvc.perform(asCustomer(email, customer, post("/api/auth/two-step/confirm"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"" + lastCodeTo(email) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(twoStepOn(email)).isTrue();
    }

    private boolean twoStepOn(String email) {
        return jdbc.queryForObject("select two_step_enabled from users where email = ?", Boolean.class, email);
    }

    /** The code in the latest email to this address. */
    private String lastCodeTo(String to) {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        List<SimpleMailMessage> mine = sent.getAllValues().stream().filter(m -> m.getTo()[0].equals(to)).toList();
        assertThat(mine).isNotEmpty();
        Matcher code = Pattern.compile("\\b(\\d{6})\\b").matcher(mine.getLast().getText());
        assertThat(code.find()).isTrue();
        return code.group(1);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private ResultActions checkCode(String codeId, String code) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/sign-in-codes"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"codeId\": \"" + codeId + "\", \"code\": \"" + code + "\"}"));
    }

    private ResultActions authenticate(String email, String password) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/authentications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
    }

    private String newLogin(long customer) throws Exception {
        String email = uniqueEmail();
        when(customerService.emailOf(customer)).thenReturn(email);
        setLogin(customer, PASSWORD).andExpect(status().isNoContent());
        return email;
    }

    private ResultActions setLogin(long customer, String password) throws Exception {
        return mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), put("/api/customers/{id}/login", customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"" + password + "\"}"));
    }

    private MockHttpServletRequestBuilder asCustomer(String email, long customer, MockHttpServletRequestBuilder request) {
        long userId = jdbc.queryForObject("select id from users where email = ?", Long.class, email);
        return as(gateway.forUser(userId, "CUSTOMER", customer), request);
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static long newCustomerId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
