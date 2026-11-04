package com.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.account.AccountService;
import com.bank.account.AccountType;
import com.bank.account.dto.CreateAccountRequest;
import com.bank.auth.LoginService;
import com.bank.customer.CustomerService;
import com.bank.customer.dto.CustomerRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Goes through the real sign-in and the real filter chain, so a mistake in the rules shows up. */
@SpringBootTest(properties = {
        "bank.admin.email=Boss@Example.com",
        "bank.admin.password=" + SecurityIntegrationTest.STAFF_PASSWORD
})
@AutoConfigureMockMvc
@Testcontainers
class SecurityIntegrationTest {

    static final String STAFF_PASSWORD = "first-staff-password";
    private static final String STAFF_EMAIL = "boss@example.com";
    private static final String PASSWORD = "customer-password";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LoginService loginService;

    @Autowired
    private JdbcTemplate jdbc;

    /** A customer with a login and one funded account. */
    private record Holder(long customerId, long accountId, String email) { }

    private Holder holder() {
        String email = UUID.randomUUID() + "@example.com";
        long customerId = customerService.create(new CustomerRequest("Ada", "Lovelace", email)).id();
        long accountId = accountService.create(new CreateAccountRequest(customerId, AccountType.CHECKING)).id();
        accountService.deposit(accountId, new BigDecimal("50.00"));
        loginService.setCustomerPassword(customerId, PASSWORD);
        return new Holder(customerId, accountId, email);
    }

    private MockHttpSession signIn(String email, String password) throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", email).param("password", password))
                .andExpect(status().isNoContent())
                .andReturn().getRequest().getSession(false);
    }

    private static final String AMOUNT = "{\"amount\": 1.00}";

    @Test
    void anonymousVisitorsGetThePageButNoData() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());

        for (String path : new String[] {"/api/customers", "/api/customers/1", "/api/accounts", "/api/accounts/1",
                "/api/accounts/1/transactions", "/api/auth/me"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/accounts/1/deposit").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anythingOutsideTheKnownFilesAndApiIsRefused() throws Exception {
        mockMvc.perform(get("/login")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/application.yaml")).andExpect(status().isUnauthorized());
    }

    @Test
    void firstStaffLoginIsCreatedFromConfigurationWithAHashedPassword() throws Exception {
        MockHttpSession staff = signIn("  BOSS@example.com ", STAFF_PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(STAFF_EMAIL))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String stored = jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, STAFF_EMAIL);
        assertThat(stored).startsWith("{bcrypt}").doesNotContain(STAFF_PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        String wrongPassword = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", STAFF_EMAIL).param("password", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", "nobody@example.com").param("password", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void customerSeesTheirOwnRecords() throws Exception {
        Holder ada = holder();
        MockHttpSession session = signIn(ada.email(), PASSWORD);

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.customerId").value(ada.customerId()));
        mockMvc.perform(get("/api/customers/{id}", ada.customerId()).session(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts").param("customerId", String.valueOf(ada.customerId())).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/api/accounts/{id}", ada.accountId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50.0));
        mockMvc.perform(get("/api/accounts/{id}/transactions", ada.accountId()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void customerCannotReadAnotherCustomersRecords() throws Exception {
        Holder ada = holder();
        Holder other = holder();
        MockHttpSession session = signIn(ada.email(), PASSWORD);

        mockMvc.perform(get("/api/customers/{id}", other.customerId()).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/{id}", other.accountId()).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts/{id}/transactions", other.accountId()).session(session))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/accounts").param("customerId", String.valueOf(other.customerId())).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotListEveryone() throws Exception {
        MockHttpSession session = signIn(holder().email(), PASSWORD);

        mockMvc.perform(get("/api/customers").session(session)).andExpect(status().isForbidden());
        // Without a customerId the endpoint would return every account in the bank.
        mockMvc.perform(get("/api/accounts").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void unknownIdLooksTheSameAsAnotherCustomersId() throws Exception {
        MockHttpSession session = signIn(holder().email(), PASSWORD);

        mockMvc.perform(get("/api/accounts/{id}", Long.MAX_VALUE).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotChangeAnythingEvenOnTheirOwnAccount() throws Exception {
        Holder ada = holder();
        MockHttpSession session = signIn(ada.email(), PASSWORD);

        for (String operation : new String[] {"deposit", "withdraw"}) {
            mockMvc.perform(post("/api/accounts/{id}/{op}", ada.accountId(), operation).session(session).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(delete("/api/accounts/{id}", ada.accountId()).session(session).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/customers/{id}", ada.customerId()).session(session).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/accounts").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": " + ada.customerId() + ", \"type\": \"SAVINGS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/customers/{id}/login", ada.customerId()).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\": \"a-brand-new-password\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/accounts/{id}", ada.accountId()).session(session))
                .andExpect(jsonPath("$.balance").value(50.0));
    }

    @Test
    void staffCanReadAndChangeAnyAccount() throws Exception {
        Holder ada = holder();
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);

        mockMvc.perform(get("/api/customers").session(staff)).andExpect(status().isOk());
        mockMvc.perform(get("/api/accounts").session(staff)).andExpect(status().isOk());
        mockMvc.perform(post("/api/accounts/{id}/deposit", ada.accountId()).session(staff).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(51.0));
    }

    @Test
    void writesWithoutACsrfTokenAreRefusedAndChangeNothing() throws Exception {
        Holder ada = holder();
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);

        mockMvc.perform(post("/api/accounts/{id}/deposit", ada.accountId()).session(staff)
                        .contentType(MediaType.APPLICATION_JSON).content(AMOUNT))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/accounts/{id}", ada.accountId()).session(staff))
                .andExpect(jsonPath("$.balance").value(50.0));
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        MockHttpSession session = signIn(holder().email(), PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void changingPasswordReplacesTheOldOne() throws Exception {
        Holder ada = holder();
        MockHttpSession session = signIn(ada.email(), PASSWORD);
        String newPassword = "a-brand-new-password";

        mockMvc.perform(post("/api/auth/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"wrong\", \"newPassword\": \"" + newPassword + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.newPassword").exists());
        mockMvc.perform(post("/api/auth/password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + newPassword + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login").with(csrf()).param("email", ada.email()).param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
        signIn(ada.email(), newPassword);
    }

    @Test
    void passwordOverTheBcryptLimitInBytesIsRefusedCleanly() throws Exception {
        Holder ada = holder();
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);

        // 40 characters, so it passes the length check, but 120 bytes in UTF-8.
        mockMvc.perform(put("/api/customers/{id}/login", ada.customerId()).session(staff).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + "€".repeat(40) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changingACustomersEmailChangesTheirSignIn() throws Exception {
        Holder ada = holder();
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);
        String newEmail = UUID.randomUUID() + "@example.com";

        mockMvc.perform(put("/api/customers/{id}", ada.customerId()).session(staff).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\": \"Ada\", \"lastName\": \"L\", \"email\": \"" + newEmail + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login").with(csrf()).param("email", ada.email()).param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
        signIn(newEmail, PASSWORD);
    }

    @Test
    void customerCannotTakeAStaffLoginsEmail() throws Exception {
        Holder ada = holder();
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);

        mockMvc.perform(put("/api/customers/{id}", ada.customerId()).session(staff).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\": \"Ada\", \"lastName\": \"L\", \"email\": \"" + STAFF_EMAIL + "\"}"))
                .andExpect(status().isConflict());

        // The whole update rolled back, so the old sign-in still works.
        signIn(ada.email(), PASSWORD);
    }

    @Test
    void deletingACustomerRemovesTheirLogin() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        long customerId = customerService.create(new CustomerRequest("Temp", "Person", email)).id();
        loginService.setCustomerPassword(customerId, PASSWORD);
        MockHttpSession staff = signIn(STAFF_EMAIL, STAFF_PASSWORD);
        MockHttpSession customer = signIn(email, PASSWORD);

        mockMvc.perform(delete("/api/customers/{id}", customerId).session(staff).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
        // A session that was already open loses access straight away.
        mockMvc.perform(get("/api/auth/me").session(customer)).andExpect(status().isUnauthorized());
    }
}
