package com.bank.e2e;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Talks to the gateway the way the front end does: keeps cookies and sends the CSRF token back. */
final class Browser {

    /** replayed: the answer to an earlier request with the same Idempotency-Key. */
    record Response(int status, String body, boolean replayed) {
        <T> T read(String jsonPath) {
            return JsonPath.read(body, jsonPath);
        }

        long id() {
            return this.<Number>read("$.id").longValue();
        }
    }

    private final int port;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client;

    Browser(int port) {
        this.port = port;
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(cookies).build();
    }

    Response get(String path) throws Exception {
        return send(request(path).GET());
    }

    Response post(String path, String json) throws Exception {
        return write("POST", path, json);
    }

    Response put(String path, String json) throws Exception {
        return write("PUT", path, json);
    }

    Response delete(String path) throws Exception {
        return send(request(path).header("X-XSRF-TOKEN", csrfToken()).DELETE());
    }

    /** A write as a page from another site could send it: with the cookies, without the token. */
    Response postWithoutCsrfToken(String path, String json) throws Exception {
        return send(request(path).header("Content-Type", "application/json").POST(BodyPublishers.ofString(json)));
    }

    Response signIn(String email, String password) throws Exception {
        String form = "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return send(request("/api/auth/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.ofString(form)));
    }

    Response signOut() throws Exception {
        return send(request("/api/auth/logout").header("X-XSRF-TOKEN", csrfToken())
                .POST(BodyPublishers.noBody()));
    }

    Response postWithHeader(String path, String json, String name, String value) throws Exception {
        return send(writeRequest("POST", path, json).header(name, value));
    }

    Response postWithKey(String path, String json, String idempotencyKey) throws Exception {
        return send(writeRequest("POST", path, json).header("Idempotency-Key", idempotencyKey));
    }

    private Response write(String method, String path, String json) throws Exception {
        return send(writeRequest(method, path, json));
    }

    private HttpRequest.Builder writeRequest(String method, String path, String json) throws Exception {
        return request(path)
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrfToken())
                .method(method, BodyPublishers.ofString(json));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private Response send(HttpRequest.Builder request) throws Exception {
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Idempotent-Replayed").isPresent());
    }

    private String csrfToken() throws Exception {
        if (csrfCookie() == null) {
            get("/index.html");
        }
        return csrfCookie();
    }

    private String csrfCookie() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN"))
                .map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }
}
