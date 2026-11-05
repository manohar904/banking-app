package com.bank.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Everything the monolith promised, checked from the outside against the four real services: the
 * packaged jars, real PostgreSQL, real sign-in and the real calls between services. Requires Docker.
 */
class BankingSystemTest {

    private static final String STAFF_PASSWORD = "first-staff-password";
    private static final String STAFF_EMAIL = "boss@example.com";
    private static final String PASSWORD = "customer-password";
    private static final String AMOUNT = "{\"amount\": 1.00}";

    private static BankingSystem system;

    /** A staff member, signed in afresh for each test. */
    private Browser staff;

    /** A customer with a login and one funded account. */
    private record Holder(long customerId, long accountId, String email) { }

    @BeforeAll
    static void startTheSystem() throws Exception {
        system = new BankingSystem();
        system.start("Boss@Example.com", STAFF_PASSWORD);
    }

    @AfterAll
    static void stopTheSystem() {
        system.close();
    }

    @BeforeEach
    void signInStaff() throws Exception {
        staff = browser();
        assertThat(staff.signIn(STAFF_EMAIL, STAFF_PASSWORD).status()).isEqualTo(204);
    }

    private static Browser browser() {
        return new Browser(system.gatewayPort);
    }

    private static String customerJson(String email) {
        return "{\"firstName\": \"Ada\", \"lastName\": \"Lovelace\", \"email\": \"" + email + "\"}";
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private long newCustomer(String email) throws Exception {
        Browser.Response response = staff.post("/api/customers", customerJson(email));
        assertThat(response.status()).isEqualTo(201);
        return response.id();
    }

    private long newAccount(long customerId) throws Exception {
        Browser.Response response = staff.post("/api/accounts",
                "{\"customerId\": " + customerId + ", \"type\": \"CHECKING\"}");
        assertThat(response.status()).isEqualTo(201);
        return response.id();
    }

    private void setLogin(long customerId) throws Exception {
        assertThat(staff.put("/api/customers/" + customerId + "/login", "{\"password\": \"" + PASSWORD + "\"}").status())
                .isEqualTo(204);
    }

    private Holder holder() throws Exception {
        String email = uniqueEmail();
        long customerId = newCustomer(email);
        long accountId = newAccount(customerId);
        assertThat(staff.post("/api/accounts/" + accountId + "/deposit", "{\"amount\": 50.00}").status()).isEqualTo(200);
        setLogin(customerId);
        return new Holder(customerId, accountId, email);
    }

    private static Browser signedIn(String email, String password) throws Exception {
        Browser browser = browser();
        assertThat(browser.signIn(email, password).status()).isEqualTo(204);
        return browser;
    }

    @Test
    void anonymousVisitorsGetThePageButNoData() throws Exception {
        Browser anonymous = browser();

        assertThat(anonymous.get("/index.html").status()).isEqualTo(200);
        assertThat(anonymous.get("/app.js").status()).isEqualTo(200);
        assertThat(anonymous.get("/styles.css").status()).isEqualTo(200);
        for (String path : new String[] {"/api/customers", "/api/customers/1", "/api/accounts", "/api/accounts/1",
                "/api/accounts/1/transactions", "/api/auth/me"}) {
            assertThat(anonymous.get(path).status()).as(path).isEqualTo(401);
        }
        assertThat(anonymous.post("/api/accounts/1/deposit", AMOUNT).status()).isEqualTo(401);
    }

    @Test
    void anythingOutsideTheKnownFilesAndApiIsRefused() throws Exception {
        assertThat(browser().get("/login").status()).isEqualTo(401);
        assertThat(browser().get("/application.yaml").status()).isEqualTo(401);
    }

    @Test
    void internalEndpointsCannotBeReachedThroughTheGateway() throws Exception {
        assertThat(staff.get("/internal/customers/1").status()).isEqualTo(403);
        assertThat(staff.post("/internal/authentications", "{}").status()).isEqualTo(403);
    }

    @Test
    void servicesRefuseAnyoneWhoCallsThemDirectlyWithoutAToken() throws Exception {
        HttpClient direct = HttpClient.newHttpClient();
        for (int port : new int[] {system.customerPort, system.accountPort, system.authPort}) {
            for (String path : new String[] {"/api/customers", "/api/accounts", "/api/auth/me", "/internal/customers/1"}) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
                assertThat(direct.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                        .as("port %d %s", port, path).isEqualTo(401);
            }
        }
    }

    @Test
    void firstStaffLoginIsCreatedFromConfiguration() throws Exception {
        Browser boss = signedIn("  BOSS@example.com ", STAFF_PASSWORD);

        Browser.Response me = boss.get("/api/auth/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.<String>read("$.email")).isEqualTo(STAFF_EMAIL);
        assertThat(me.<String>read("$.role")).isEqualTo("STAFF");
        assertThat(me.body()).doesNotContain("password");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() throws Exception {
        Browser.Response wrongPassword = browser().signIn(STAFF_EMAIL, "not-the-password");
        Browser.Response unknownEmail = browser().signIn("nobody@example.com", "not-the-password");

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownEmail).isEqualTo(wrongPassword);
    }

    @Test
    void amountsKeepTheirExactDigitsAllTheWayThrough() throws Exception {
        long account = newAccount(newCustomer(uniqueEmail()));

        Browser.Response response = staff.post("/api/accounts/" + account + "/deposit", "{\"amount\": 100.10}");

        assertThat(response.body()).contains("\"balance\":100.10");
    }

    @Test
    void customerSeesTheirOwnRecords() throws Exception {
        Holder ada = holder();
        Browser session = signedIn(ada.email(), PASSWORD);

        Browser.Response me = session.get("/api/auth/me");
        assertThat(me.<String>read("$.role")).isEqualTo("CUSTOMER");
        assertThat(me.<Number>read("$.customerId").longValue()).isEqualTo(ada.customerId());
        assertThat(session.get("/api/customers/" + ada.customerId()).status()).isEqualTo(200);

        Browser.Response accounts = session.get("/api/accounts?customerId=" + ada.customerId());
        assertThat(accounts.status()).isEqualTo(200);
        assertThat(accounts.<Number>read("$.page.totalElements").intValue()).isEqualTo(1);

        Browser.Response account = session.get("/api/accounts/" + ada.accountId());
        assertThat(account.status()).isEqualTo(200);
        assertThat(account.<Number>read("$.balance").doubleValue()).isEqualTo(50.0);

        Browser.Response history = session.get("/api/accounts/" + ada.accountId() + "/transactions");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.<Number>read("$.page.totalElements").intValue()).isEqualTo(1);
    }

    @Test
    void customerCannotReadAnotherCustomersRecords() throws Exception {
        Holder ada = holder();
        Holder other = holder();
        Browser session = signedIn(ada.email(), PASSWORD);

        assertThat(session.get("/api/customers/" + other.customerId()).status()).isEqualTo(403);
        assertThat(session.get("/api/accounts/" + other.accountId()).status()).isEqualTo(403);
        assertThat(session.get("/api/accounts/" + other.accountId() + "/transactions").status()).isEqualTo(403);
        assertThat(session.get("/api/accounts?customerId=" + other.customerId()).status()).isEqualTo(403);
    }

    @Test
    void customerCannotListEveryone() throws Exception {
        Browser session = signedIn(holder().email(), PASSWORD);

        assertThat(session.get("/api/customers").status()).isEqualTo(403);
        // Without a customerId the endpoint would return every account in the bank.
        assertThat(session.get("/api/accounts").status()).isEqualTo(403);
    }

    @Test
    void unknownIdLooksTheSameAsAnotherCustomersId() throws Exception {
        Browser session = signedIn(holder().email(), PASSWORD);

        assertThat(session.get("/api/accounts/" + Long.MAX_VALUE).status()).isEqualTo(403);
    }

    @Test
    void customerCannotChangeAnythingEvenOnTheirOwnAccount() throws Exception {
        Holder ada = holder();
        Browser session = signedIn(ada.email(), PASSWORD);

        for (String operation : new String[] {"deposit", "withdraw"}) {
            assertThat(session.post("/api/accounts/" + ada.accountId() + "/" + operation, AMOUNT).status())
                    .isEqualTo(403);
        }
        assertThat(session.delete("/api/accounts/" + ada.accountId()).status()).isEqualTo(403);
        assertThat(session.delete("/api/customers/" + ada.customerId()).status()).isEqualTo(403);
        assertThat(session.post("/api/accounts",
                "{\"customerId\": " + ada.customerId() + ", \"type\": \"SAVINGS\"}").status()).isEqualTo(403);
        assertThat(session.put("/api/customers/" + ada.customerId() + "/login",
                "{\"password\": \"a-brand-new-password\"}").status()).isEqualTo(403);

        assertThat(session.get("/api/accounts/" + ada.accountId()).<Number>read("$.balance").doubleValue())
                .isEqualTo(50.0);
    }

    @Test
    void staffCanReadAndChangeAnyAccount() throws Exception {
        Holder ada = holder();

        assertThat(staff.get("/api/customers").status()).isEqualTo(200);
        assertThat(staff.get("/api/accounts").status()).isEqualTo(200);
        Browser.Response deposit = staff.post("/api/accounts/" + ada.accountId() + "/deposit", AMOUNT);
        assertThat(deposit.status()).isEqualTo(200);
        assertThat(deposit.<Number>read("$.balance").doubleValue()).isEqualTo(51.0);
    }

    @Test
    void writesWithoutACsrfTokenAreRefusedAndChangeNothing() throws Exception {
        Holder ada = holder();

        assertThat(staff.postWithoutCsrfToken("/api/accounts/" + ada.accountId() + "/deposit", AMOUNT).status())
                .isEqualTo(403);

        assertThat(staff.get("/api/accounts/" + ada.accountId()).<Number>read("$.balance").doubleValue())
                .isEqualTo(50.0);
    }

    @Test
    void signOutEndsTheSession() throws Exception {
        Browser session = signedIn(holder().email(), PASSWORD);
        assertThat(session.get("/api/auth/me").status()).isEqualTo(200);

        assertThat(session.signOut().status()).isEqualTo(204);

        assertThat(session.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void changingPasswordReplacesTheOldOne() throws Exception {
        Holder ada = holder();
        Browser session = signedIn(ada.email(), PASSWORD);
        String newPassword = "a-brand-new-password";

        assertThat(session.post("/api/auth/password",
                "{\"currentPassword\": \"wrong\", \"newPassword\": \"" + newPassword + "\"}").status()).isEqualTo(400);
        Browser.Response tooShort = session.post("/api/auth/password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"short\"}");
        assertThat(tooShort.status()).isEqualTo(400);
        assertThat(tooShort.<String>read("$.errors.newPassword")).isNotEmpty();
        assertThat(session.post("/api/auth/password",
                "{\"currentPassword\": \"" + PASSWORD + "\", \"newPassword\": \"" + newPassword + "\"}").status())
                .isEqualTo(204);

        assertThat(browser().signIn(ada.email(), PASSWORD).status()).isEqualTo(401);
        signedIn(ada.email(), newPassword);
    }

    @Test
    void passwordOverTheBcryptLimitInBytesIsRefusedCleanly() throws Exception {
        Holder ada = holder();

        // 40 characters, so it passes the length check, but 120 bytes in UTF-8.
        assertThat(staff.put("/api/customers/" + ada.customerId() + "/login",
                "{\"password\": \"" + "€".repeat(40) + "\"}").status()).isEqualTo(400);
    }

    // The rules that used to come from sharing one database.

    @Test
    void accountCannotBeOpenedForAnUnknownCustomer() throws Exception {
        assertThat(staff.post("/api/accounts", "{\"customerId\": " + Long.MAX_VALUE + ", \"type\": \"CHECKING\"}").status())
                .isEqualTo(404);
    }

    @Test
    void loginCannotBeSetForAnUnknownCustomer() throws Exception {
        assertThat(staff.put("/api/customers/" + Long.MAX_VALUE + "/login", "{\"password\": \"" + PASSWORD + "\"}").status())
                .isEqualTo(404);
    }

    @Test
    void changingACustomersEmailChangesTheirSignIn() throws Exception {
        Holder ada = holder();
        String newEmail = uniqueEmail();

        assertThat(staff.put("/api/customers/" + ada.customerId(), customerJson(newEmail)).status()).isEqualTo(200);

        assertThat(browser().signIn(ada.email(), PASSWORD).status()).isEqualTo(401);
        signedIn(newEmail, PASSWORD);
    }

    @Test
    void customerCannotTakeAStaffLoginsEmail() throws Exception {
        Holder ada = holder();

        assertThat(staff.put("/api/customers/" + ada.customerId(), customerJson(STAFF_EMAIL)).status()).isEqualTo(409);

        // The whole update rolled back, so the customer and the old sign-in are unchanged.
        assertThat(staff.get("/api/customers/" + ada.customerId()).<String>read("$.email")).isEqualTo(ada.email());
        signedIn(ada.email(), PASSWORD);
    }

    @Test
    void customerWithAccountsCannotBeDeletedAndKeepsEverything() throws Exception {
        Holder ada = holder();

        assertThat(staff.delete("/api/customers/" + ada.customerId()).status()).isEqualTo(409);

        assertThat(staff.get("/api/customers/" + ada.customerId()).status()).isEqualTo(200);
        signedIn(ada.email(), PASSWORD);
        // Being refused does not stop them getting another account.
        newAccount(ada.customerId());
    }

    @Test
    void unusedAccountAndCustomerCanBeDeleted() throws Exception {
        long customer = newCustomer(uniqueEmail());
        long account = newAccount(customer);

        assertThat(staff.delete("/api/accounts/" + account).status()).isEqualTo(204);
        assertThat(staff.delete("/api/customers/" + customer).status()).isEqualTo(204);

        assertThat(staff.get("/api/accounts/" + account).status()).isEqualTo(404);
        assertThat(staff.get("/api/customers/" + customer).status()).isEqualTo(404);
    }

    @Test
    void deletingACustomerRemovesTheirLogin() throws Exception {
        String email = uniqueEmail();
        long customerId = newCustomer(email);
        setLogin(customerId);
        Browser customer = signedIn(email, PASSWORD);

        assertThat(staff.delete("/api/customers/" + customerId).status()).isEqualTo(204);

        assertThat(browser().signIn(email, PASSWORD).status()).isEqualTo(401);
        // A session that was already open loses access straight away.
        assertThat(customer.get("/api/auth/me").status()).isEqualTo(401);
    }

    @Test
    void deletedCustomerCannotGetAnAccount() throws Exception {
        long customer = newCustomer(uniqueEmail());
        assertThat(staff.delete("/api/customers/" + customer).status()).isEqualTo(204);

        assertThat(staff.post("/api/accounts", "{\"customerId\": " + customer + ", \"type\": \"CHECKING\"}").status())
                .isEqualTo(404);
    }
}
