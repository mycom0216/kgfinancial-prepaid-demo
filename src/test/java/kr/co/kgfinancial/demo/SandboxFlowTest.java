package kr.co.kgfinancial.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("sandbox")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.admin.username=demo-admin", "app.admin.password=integration-test-secret"
})
class SandboxFlowTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @LocalServerPort
    private int port;

    @Test
    void browserFlowProtectsCardsAndRecordsEachBalanceChangeOnce() throws Exception {
        try (var client = new Browser()) {
            assertThat(client.get("/").statusCode()).isEqualTo(200);
            assertThat(client.get("/api/auth/me").statusCode()).isEqualTo(401);
            assertThat(client.post("/api/auth/register", "{\"username\":\"member\",\"password\":\"local-test-password\"}", null, null)
                    .statusCode()).isEqualTo(403);
            assertThat(client.register("member").statusCode()).isEqualTo(200);
            assertThat(client.login("member", "local-test-password").statusCode()).isEqualTo(200);
            long card = body(client.post("/api/cards", "", client.csrf(), null)).get("id").asLong();
            String funds = "/api/sandbox/cards/" + card + "/top-up";
            String pay = "/api/cards/" + card + "/pay";
            String history = "/api/cards/" + card + "/transactions";
            String token = client.csrf();

            assertThat(client.post(funds, "{\"amount\":1000}", token, "short").statusCode()).isEqualTo(400);
            var first = client.postAsync(funds, "{\"amount\":1000}", token, "sandbox-top-up-once");
            var retry = client.postAsync(funds, "{\"amount\":1000}", token, "sandbox-top-up-once");
            for (var response : List.of(first.get(), retry.get())) {
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(body(response).get("balance").asLong()).isEqualTo(1000);
            }
            assertThat(client.post(funds, "{\"amount\":600}", token, "sandbox-top-up-once").statusCode()).isEqualTo(409);

            var payment = client.postAsync(pay, "{\"amount\":700}", token, "practice-payment-one");
            var concurrent = client.postAsync(pay, "{\"amount\":700}", token, "practice-payment-two");
            assertThat(List.of(payment.get().statusCode(), concurrent.get().statusCode())).containsExactlyInAnyOrder(200, 400);
            assertThat(body(client.get("/api/cards")).get(0).get("balance").asLong()).isEqualTo(300);
            var transactions = body(client.get(history));
            assertThat(transactions.size()).isEqualTo(2);
            assertThat(transactions.get(0).get("kind").asString()).isEqualTo("연습 결제");
            assertThat(transactions.get(1).get("kind").asString()).isEqualTo("샌드박스 충전");
            assertThat(transactions.get(0).has("idempotencyKey")).isFalse();
            assertThat(client.get("/api/admin/audit-logs").statusCode()).isEqualTo(403);
            assertThat(client.post("/api/cards/" + card + "/charge", "{\"amount\":1000}", token, "gateway-charge-key")
                    .statusCode()).isEqualTo(503);

            try (var other = new Browser()) {
                assertThat(other.register("other").statusCode()).isEqualTo(200);
                assertThat(other.login("other", "local-test-password").statusCode()).isEqualTo(200);
                assertThat(other.get(history).statusCode()).isEqualTo(404);
                assertThat(other.post(funds, "{\"amount\":1000}", other.csrf(), "other-top-up-key").statusCode()).isEqualTo(404);
            }
            assertThat(client.post("/api/auth/logout", "", client.csrf(), null).statusCode()).isEqualTo(204);
            assertThat(client.get("/api/cards").statusCode()).isEqualTo(401);

            assertThat(client.login("demo-admin", "integration-test-secret").statusCode()).isEqualTo(200);
            assertThat(body(client.get("/api/admin/cards")).size()).isEqualTo(1);
            assertThat(body(client.get("/api/admin/audit-logs")).get(0).has("action")).isTrue();
        }
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager();
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        String csrf() throws IOException, InterruptedException {
            return body(get("/api/auth/csrf")).get("token").asString();
        }

        HttpResponse<String> register(String username) throws IOException, InterruptedException {
            return post("/api/auth/register", json.writeValueAsString(Map.of(
                    "username", username, "password", "local-test-password")), csrf(), null);
        }

        HttpResponse<String> login(String username, String password) throws IOException, InterruptedException {
            return http.send(request("/api/auth/login")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-XSRF-TOKEN", csrf())
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        }

        HttpResponse<String> post(String path, String payload, String token, String key)
                throws IOException, InterruptedException {
            return http.send(postRequest(path, payload, token, key), HttpResponse.BodyHandlers.ofString());
        }

        CompletableFuture<HttpResponse<String>> postAsync(String path, String payload, String token, String key) {
            return http.sendAsync(postRequest(path, payload, token, key), HttpResponse.BodyHandlers.ofString());
        }

        private HttpRequest postRequest(String path, String payload, String token, String key) {
            var builder = request(path).header("Content-Type", "application/json");
            if (token != null) builder.header("X-XSRF-TOKEN", token);
            if (key != null) builder.header("Idempotency-Key", key);
            return builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        }

        private HttpRequest.Builder request(String path) {
            return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "/".equals(path) ? "text/html" : "application/json");
        }

        @Override
        public void close() {
            cookies.getCookieStore().removeAll();
        }
    }
}
