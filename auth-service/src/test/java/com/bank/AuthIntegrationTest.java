package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.auth.CustomerServiceClient;
import com.bank.common.ResourceNotFoundException;
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

/**
 * Runs the service against real PostgreSQL, through its real security rules. Requires Docker. The
 * customer service is stubbed; e2e-tests covers the services working together.
 */
@SpringBootTest(properties = {
        "bank.kafka.enabled=false",
        "bank.sign-in.max-failures-per-address=" + AuthIntegrationTest.MAX_FAILURES_PER_ADDRESS,
        "bank.password-reset.from=bank@example.com",
        "bank.password-reset.base-url=https://bank.example",
        "bank.admin.email=Boss@Example.com",
        "bank.admin.password=" + AuthIntegrationTest.STAFF_PASSWORD
})
@AutoConfigureMockMvc
@Testcontainers
class AuthIntegrationTest {

    static final String STAFF_PASSWORD = "first-staff-password";
    private static final String STAFF_EMAIL = "boss@example.com";
    private static final String PASSWORD = "customer-password";
    private static final int MAX_ATTEMPTS = 5;
    static final int MAX_FAILURES_PER_ADDRESS = 8;

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
    private CustomerServiceClient customerService;

    @MockitoBean
    private JavaMailSender mail;

    @Test
    void firstStaffLoginIsCreatedFromConfigurationWithAHashedPassword() {
        String stored = jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, STAFF_EMAIL);

        assertThat(stored).startsWith("{bcrypt}").doesNotContain(STAFF_PASSWORD);
    }

    @Test
    void checkingAPasswordIgnoresEmailCaseAndSpacesAndNeverReturnsTheHash() throws Exception {
        authenticate("  BOSS@example.com ", STAFF_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(STAFF_EMAIL))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        String wrongPassword = authenticate(STAFF_EMAIL, "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = authenticate("nobody@example.com", "not-the-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void afterFiveAttemptsEvenTheRightPasswordIsRefused() throws Exception {
        String email = newLogin(newCustomerId());

        useUpAttempts(email);

        authenticate(email, PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void theLimitCannotBeAvoidedByChangingEmailCase() throws Exception {
        String email = newLogin(newCustomerId());

        useUpAttempts(email.toUpperCase());

        authenticate("  " + email + " ", PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void anEmailWithoutAnAccountIsLimitedTheSameWay() throws Exception {
        String known = newLogin(newCustomerId());
        String unknown = uniqueEmail();
        useUpAttempts(known);
        useUpAttempts(unknown);

        String knownAnswer = authenticate(known, "not-the-password")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();
        String unknownAnswer = authenticate(unknown, "not-the-password")
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownAnswer).isEqualTo(knownAnswer);
    }

    @Test
    void aSuccessfulSignInStartsTheCountAgain() throws Exception {
        String email = newLogin(newCustomerId());
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            authenticate(email, "not-the-password").andExpect(status().isUnauthorized());
        }

        authenticate(email, PASSWORD).andExpect(status().isOk());

        useUpAttempts(email);
    }

    @Test
    void signingInWorksAgainOnceTheWindowHasPassed() throws Exception {
        String email = newLogin(newCustomerId());
        useUpAttempts(email);
        authenticate(email, PASSWORD).andExpect(status().isTooManyRequests());

        jdbc.update("update sign_in_attempts set window_started_at = window_started_at - interval '16 minutes'");

        authenticate(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void staffSettingANewPasswordLiftsTheLimit() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        useUpAttempts(email);

        setLogin(customer, "a-brand-new-password").andExpect(status().isNoContent());

        authenticate(email, "a-brand-new-password").andExpect(status().isOk());
    }

    @Test
    void oneEmailReachingTheLimitDoesNotAffectAnother() throws Exception {
        String limited = newLogin(newCustomerId());
        String other = newLogin(newCustomerId());

        useUpAttempts(limited);

        authenticate(other, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void oneAddressFailingOnManyEmailsIsStopped() throws Exception {
        String address = newAddress();
        for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
            authenticateFrom(address, uniqueEmail(), "not-the-password").andExpect(status().isUnauthorized());
        }
        String email = newLogin(newCustomerId());

        authenticateFrom(address, email, PASSWORD).andExpect(status().isTooManyRequests());

        // The refused attempt was not counted against the email, and other addresses are not affected.
        authenticateFrom(newAddress(), email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void successfulSignInsFromOneAddressAreNotLimited() throws Exception {
        String address = newAddress();
        String email = newLogin(newCustomerId());

        for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS + 2; attempt++) {
            authenticateFrom(address, email, PASSWORD).andExpect(status().isOk());
        }
        authenticateFrom(address, uniqueEmail(), "not-the-password").andExpect(status().isUnauthorized());
    }

    @Test
    void aRightPasswordDoesNotWipeOutEarlierFailuresFromTheAddress() throws Exception {
        String address = newAddress();
        String email = newLogin(newCustomerId());
        for (int attempt = 1; attempt <= MAX_FAILURES_PER_ADDRESS; attempt++) {
            authenticateFrom(address, uniqueEmail(), "not-the-password").andExpect(status().isUnauthorized());
            if (attempt < MAX_FAILURES_PER_ADDRESS) {
                authenticateFrom(address, email, PASSWORD).andExpect(status().isOk());
            }
        }

        authenticateFrom(address, uniqueEmail(), "not-the-password").andExpect(status().isTooManyRequests());
    }

    @Test
    void addressesAreNotStoredWithTheAttempts() throws Exception {
        String address = newAddress();
        authenticateFrom(address, uniqueEmail(), "not-the-password");

        assertThat(jdbc.queryForList("select email_hash from sign_in_attempts", String.class))
                .noneMatch(hash -> hash.contains(address));
    }

    @Test
    void emailsAreNotStoredWithTheAttempts() throws Exception {
        String email = uniqueEmail();
        authenticate(email, "not-the-password");

        List<String> stored = jdbc.queryForList("select email_hash from sign_in_attempts", String.class);

        assertThat(stored).isNotEmpty().noneMatch(hash -> hash.contains("@")).allMatch(hash -> hash.length() == 64);
    }

    @Test
    void settingALoginForAnUnknownCustomerReturns404() throws Exception {
        long customer = newCustomerId();
        when(customerService.emailOf(customer))
                .thenThrow(new ResourceNotFoundException("Customer " + customer + " not found."));

        setLogin(customer, PASSWORD).andExpect(status().isNotFound());
    }

    @Test
    void customerCanSignInOnceALoginIsSetAndResettingReplacesThePassword() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);

        authenticate(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").value(customer));

        setLogin(customer, "a-brand-new-password").andExpect(status().isNoContent());

        authenticate(email, PASSWORD).andExpect(status().isUnauthorized());
        authenticate(email, "a-brand-new-password").andExpect(status().isOk());
    }

    @Test
    void passwordOverTheBcryptLimitInBytesIsRefusedCleanly() throws Exception {
        // 40 characters, so it passes the length check, but 120 bytes in UTF-8.
        setLogin(newCustomerId(), "€".repeat(40)).andExpect(status().isBadRequest());
    }

    @Test
    void emailChangeMovesTheSignIn() throws Exception {
        long customer = newCustomerId();
        String oldEmail = newLogin(customer);
        String newEmail = uniqueEmail();

        changeEmail(customer, newEmail).andExpect(status().isNoContent());

        authenticate(oldEmail, PASSWORD).andExpect(status().isUnauthorized());
        authenticate(newEmail, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void emailChangeForACustomerWithoutALoginDoesNothing() throws Exception {
        changeEmail(newCustomerId(), uniqueEmail()).andExpect(status().isNoContent());
    }

    @Test
    void customerCannotTakeAStaffLoginsEmail() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);

        changeEmail(customer, STAFF_EMAIL).andExpect(status().isConflict());

        authenticate(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void deletingALoginIsSafeToRepeatAndEndsTheSignIn() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);

        deleteLogin(customer).andExpect(status().isNoContent());
        deleteLogin(customer).andExpect(status().isNoContent());

        authenticate(email, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void meComesFromTheDatabaseSoARemovedLoginIsSignedOut() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        Number userId = JsonPath.read(authenticate(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.userId");
        String token = gateway.forUser(userId.longValue(), "CUSTOMER", customer);

        mockMvc.perform(as(token, get("/api/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.customerId").value(customer));

        deleteLogin(customer);

        mockMvc.perform(as(token, get("/api/auth/me"))).andExpect(status().isUnauthorized());
    }

    @Test
    void eventsNeverContainPasswordsOrEmails() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        changeEmail(customer, uniqueEmail());
        deleteLogin(customer);

        List<String> events = jdbc.queryForList(
                "select payload from outbox_events where aggregate_id = ? order by id", String.class,
                String.valueOf(customer));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).contains("LOGIN_SET");
        assertThat(events.get(1)).contains("LOGIN_EMAIL_CHANGED");
        assertThat(events.get(2)).contains("LOGIN_REMOVED");
        assertThat(String.join("", events)).doesNotContain(PASSWORD, email, "@example.com", "bcrypt");
    }

    @Test
    void onlyStaffMaySetALogin() throws Exception {
        long customer = newCustomerId();

        mockMvc.perform(as(gateway.forUser(1L, "CUSTOMER", customer), put("/api/customers/{id}/login", customer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\": \"a-brand-new-password\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyOtherServicesMayCheckPasswords() throws Exception {
        String body = "{\"email\": \"" + STAFF_EMAIL + "\", \"password\": \"" + STAFF_PASSWORD + "\"}";

        mockMvc.perform(post("/internal/authentications").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), post("/internal/authentications"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void serviceTokenIsNotAPerson() throws Exception {
        mockMvc.perform(as(tokens.forThisService(), get("/api/auth/me"))).andExpect(status().isForbidden());
    }

    @Test
    void auditLogTellsAPersonFromAnotherService() throws Exception {
        long customer = newCustomerId();
        newLogin(customer);
        deleteLogin(customer).andExpect(status().isNoContent());

        mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), get("/api/auth/audit"))
                        .param("targetId", String.valueOf(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("LOGIN_REMOVED"))
                .andExpect(jsonPath("$.content[0].actorRole").value("SERVICE"))
                .andExpect(jsonPath("$.content[0].actorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[1].action").value("LOGIN_SET"))
                .andExpect(jsonPath("$.content[1].actorRole").value("STAFF"))
                .andExpect(jsonPath("$.content[1].actorUserId").value(0));
    }

    @Test
    void customerCannotReadTheAuditLog() throws Exception {
        long customer = newCustomerId();

        mockMvc.perform(as(gateway.forUser(1L, "CUSTOMER", customer), get("/api/auth/audit")))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCanLookUpTheEmailsBehindAuditEntries() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        Number userId = JsonPath.read(authenticate(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.userId");

        mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), get("/api/auth/audit/actors"))
                        .param("ids", userId + "," + Long.MAX_VALUE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['" + userId + "']").value(email))
                .andExpect(jsonPath("$['" + Long.MAX_VALUE + "']").doesNotExist());
    }

    @Test
    void customerCannotLookUpEmails() throws Exception {
        long customer = newCustomerId();

        mockMvc.perform(as(gateway.forUser(1L, "CUSTOMER", customer), get("/api/auth/audit/actors")).param("ids", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tooManyIdsAtOnceAreRefused() throws Exception {
        String ids = String.join(",", java.util.Collections.nCopies(101, "1"));

        mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), get("/api/auth/audit/actors")).param("ids", ids))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aResetLinkChangesThePasswordAndPointsAtTheConfiguredAddress() throws Exception {
        String email = newLogin(newCustomerId());

        requestReset(email).andExpect(status().isAccepted());
        SimpleMailMessage message = resetEmails(email, 1).getFirst();
        assertThat(message.getText()).contains("https://bank.example/#reset=");
        assertThat(message.getFrom()).isEqualTo("bank@example.com");

        confirmReset(tokenIn(message), "a-brand-new-password").andExpect(status().isNoContent());

        authenticate(email, PASSWORD).andExpect(status().isUnauthorized());
        authenticate(email, "a-brand-new-password").andExpect(status().isOk());
    }

    @Test
    void aResetLinkWorksOnlyOnce() throws Exception {
        String email = newLogin(newCustomerId());
        requestReset(email);
        String token = tokenIn(resetEmails(email, 1).getFirst());

        confirmReset(token, "a-brand-new-password").andExpect(status().isNoContent());

        confirmReset(token, "another-new-password").andExpect(status().isBadRequest());
        authenticate(email, "another-new-password").andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredResetLinkIsRefused() throws Exception {
        String email = newLogin(newCustomerId());
        requestReset(email);
        String token = tokenIn(resetEmails(email, 1).getFirst());
        jdbc.update("update password_reset_tokens set expires_at = now() - interval '1 minute'");

        confirmReset(token, "a-brand-new-password").andExpect(status().isBadRequest());

        authenticate(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void askingAgainCancelsTheEarlierLink() throws Exception {
        String email = newLogin(newCustomerId());
        requestReset(email);
        requestReset(email);
        List<SimpleMailMessage> emails = resetEmails(email, 2);

        confirmReset(tokenIn(emails.get(0)), "a-brand-new-password").andExpect(status().isBadRequest());
        confirmReset(tokenIn(emails.get(1)), "a-brand-new-password").andExpect(status().isNoContent());
    }

    @Test
    void anEmailWithoutAnAccountGetsTheSameAnswerAndNoEmail() throws Exception {
        String unknown = uniqueEmail();

        requestReset(unknown).andExpect(status().isAccepted()).andExpect(content().string(""));

        verify(mail, after(300).never()).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(unknown)));
    }

    @Test
    void resetEmailsToOneAddressAreLimited() throws Exception {
        String email = newLogin(newCustomerId());

        for (int request = 1; request <= 5; request++) {
            requestReset(email).andExpect(status().isAccepted());
        }

        resetEmails(email, 3);
        verify(mail, after(300).times(3)).send(argThat((SimpleMailMessage m) -> m.getTo()[0].equals(email)));
    }

    @Test
    void aTooShortPasswordIsRefusedAndTheLinkStillWorks() throws Exception {
        String email = newLogin(newCustomerId());
        requestReset(email);
        String token = tokenIn(resetEmails(email, 1).getFirst());

        confirmReset(token, "short").andExpect(status().isBadRequest());
        // 40 characters, so it passes the length check, but over BCrypt's limit in bytes.
        confirmReset(token, "€".repeat(40)).andExpect(status().isBadRequest());

        confirmReset(token, "a-brand-new-password").andExpect(status().isNoContent());
    }

    @Test
    void aResetLiftsTheSignInLimit() throws Exception {
        String email = newLogin(newCustomerId());
        useUpAttempts(email);
        requestReset(email);

        confirmReset(tokenIn(resetEmails(email, 1).getFirst()), "a-brand-new-password").andExpect(status().isNoContent());

        authenticate(email, "a-brand-new-password").andExpect(status().isOk());
    }

    @Test
    void staffSettingAPasswordCancelsAnOutstandingLink() throws Exception {
        long customer = newCustomerId();
        String email = newLogin(customer);
        requestReset(email);
        String token = tokenIn(resetEmails(email, 1).getFirst());

        setLogin(customer, "set-by-staff-password").andExpect(status().isNoContent());

        confirmReset(token, "a-brand-new-password").andExpect(status().isBadRequest());
    }

    @Test
    void theLinkItselfIsNotStored() throws Exception {
        String email = newLogin(newCustomerId());
        requestReset(email);
        String token = tokenIn(resetEmails(email, 1).getFirst());

        assertThat(jdbc.queryForList("select token_hash from password_reset_tokens", String.class)).doesNotContain(token);
    }

    @Test
    void aResetIsAudited() throws Exception {
        String email = newLogin(newCustomerId());
        Number userId = JsonPath.read(authenticate(email, PASSWORD).andReturn().getResponse().getContentAsString(), "$.userId");
        requestReset(email);

        confirmReset(tokenIn(resetEmails(email, 1).getFirst()), "a-brand-new-password").andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select action from audit_log where target_id = ? order by id desc limit 1",
                String.class, String.valueOf(userId))).isEqualTo("PASSWORD_RESET");
    }

    @Test
    void onlyOtherServicesMayStartAReset() throws Exception {
        String body = "{\"email\": \"" + STAFF_EMAIL + "\"}";

        mockMvc.perform(post("/internal/password-reset-requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), post("/internal/password-reset-requests"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/password-reset-requests"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\"}"));
    }

    private ResultActions confirmReset(String token, String password) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/password-resets"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"" + token + "\", \"newPassword\": \"" + password + "\"}"));
    }

    /** Emails go out in the background, so this waits for them. In the order they were sent. */
    private List<SimpleMailMessage> resetEmails(String to, int expected) {
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, timeout(5000).times(expected)).send(argThat((SimpleMailMessage m) -> m != null && m.getTo()[0].equals(to)));
        verify(mail, atLeast(expected)).send(sent.capture());
        return sent.getAllValues().stream().filter(m -> m.getTo()[0].equals(to)).toList();
    }

    private static String tokenIn(SimpleMailMessage message) {
        Matcher link = Pattern.compile("#reset=([\\w-]+)").matcher(message.getText());
        assertThat(link.find()).isTrue();
        return link.group(1);
    }

    private void useUpAttempts(String email) throws Exception {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            authenticate(email, "not-the-password").andExpect(status().isUnauthorized());
        }
    }

    /** Creates a login for a customer the stub says exists, and returns the email. */
    private String newLogin(long customer) throws Exception {
        String email = uniqueEmail();
        when(customerService.emailOf(customer)).thenReturn(email);
        setLogin(customer, PASSWORD).andExpect(status().isNoContent());
        return email;
    }

    private ResultActions authenticate(String email, String password) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/authentications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
    }

    private ResultActions authenticateFrom(String address, String email, String password) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), post("/internal/authentications"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password
                        + "\", \"clientAddress\": \"" + address + "\"}"));
    }

    /** A different made-up address for each test, so tests do not use up each other's limit. */
    private static String newAddress() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    }

    private ResultActions setLogin(long customer, String password) throws Exception {
        return mockMvc.perform(as(gateway.forUser(0L, "STAFF", null), put("/api/customers/{id}/login", customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"" + password + "\"}"));
    }

    private ResultActions changeEmail(long customer, String email) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), put("/internal/logins/{id}/email", customer))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\"}"));
    }

    private ResultActions deleteLogin(long customer) throws Exception {
        return mockMvc.perform(as(tokens.forThisService(), delete("/internal/logins/{id}", customer)));
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
