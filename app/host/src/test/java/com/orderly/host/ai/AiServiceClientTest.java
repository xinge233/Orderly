package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderly.host.api.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiServiceClientTest {

    @Test
    void proxiesSettingsWithIpcTokenAndNeverExposesKeyInSettingsRequest() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (Server server = Server.start(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if ("PUT".equals(exchange.getRequestMethod())) {
                body.set(readBody(exchange));
            }
            writeJson(exchange, 200, """
                    {
                      "mode": "DEEPSEEK",
                      "baseUrl": "https://api.deepseek.com",
                      "model": "deepseek-flash",
                      "configured": true,
                      "allowDeleteDirect": false,
                      "allowSendQueryResultsToModel": false,
                      "allowLocalProvider": false
                    }
                    """);
        })) {
            AiServiceClient client = client(server.url(), "ipc-token");
            AiContracts.SettingsView view = client.updateSettings(
                    new AiContracts.SettingsRequest(
                            AiContracts.Mode.DEEPSEEK,
                            "temporary-key",
                            null,
                            null,
                            false,
                            false
                    )
            );

            assertThat(view.configured()).isTrue();
            assertThat(authorization.get()).isEqualTo("Bearer ipc-token");
            assertThat(body.get()).contains("\"temporary-key\"");
            assertThat(body.get()).contains("\"allowSendQueryResultsToModel\":false");
        }
    }

    @Test
    void reportsUnavailableWhenPythonServiceCannotBeReached() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        AiServiceClient client = client(
                "http://127.0.0.1:" + port,
                ""
        );

        assertThatThrownBy(client::settings)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(503);
                    assertThat(exception.code()).isEqualTo("AI_SERVICE_UNAVAILABLE");
                });
    }

    @Test
    void rejectsMalformedOrOversizedDecisionResponses() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(
                "{\"kind\":\"QUERY\",\"conversationId\":\"c1\",\"queries\":["
                        + query("q1") + "," + query("q2") + "," + query("q3")
                        + "," + query("q4") + "," + query("q5") + "]}"
        );
        try (Server server = Server.start(exchange -> writeJson(exchange, 200, response.get()))) {
            AiServiceClient client = client(server.url(), "");
            AiContracts.ServiceTurnRequest request = new AiContracts.ServiceTurnRequest(
                    "查询",
                    null,
                    "client-1",
                    false,
                    List.of()
            );

            assertThatThrownBy(() -> client.createTurn(request))
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.status().value()).isEqualTo(502);
                        assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE");
                    });

            response.set("{not-json");
            assertThatThrownBy(() -> client.createTurn(request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE"));
        }
    }

    @Test
    void acceptsFourQueriesAndAllowsOnlyFinalKindsAfterQueryResults() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(
                "{\"kind\":\"QUERY\",\"conversationId\":\"c1\",\"queries\":["
                        + query("q1") + "," + query("q2") + "," + query("q3")
                        + "," + query("q4") + "]}"
        );
        AtomicReference<String> continuationBody = new AtomicReference<>();
        try (Server server = Server.start(exchange -> {
            if ("/v1/turns/query-results".equals(exchange.getRequestURI().getPath())) {
                continuationBody.set(readBody(exchange));
            }
            writeJson(exchange, 200, response.get());
        })) {
            AiServiceClient client = client(server.url(), "");
            AiContracts.ServiceDecision decision = client.createTurn(
                    new AiContracts.ServiceTurnRequest(
                            "查询",
                            null,
                            "client-2",
                            false,
                            List.of()
                    )
            );
            assertThat(decision.queries()).hasSize(4);

            response.set("{\"kind\":\"QUERY\",\"conversationId\":\"c1\",\"queries\":["
                    + query("q1") + "]}");
            assertThatThrownBy(() -> client.continueWithQueryResults(
                    new AiContracts.ServiceQueryContinuationRequest(
                            "看看待办",
                            "c1",
                            "client-2#query-results",
                            false,
                            List.of(),
                            List.of(new AiContracts.QueryResult(
                                    "module_todo",
                                    "todo.list",
                                    AiContracts.QueryStatus.OK,
                                    java.util.Map.of("items", List.of()),
                                    "查询完成"
                            ))
                    )
            )).isInstanceOfSatisfying(ApiException.class, exception ->
                    assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE"));
            assertThat(continuationBody.get())
                    .contains("\"goal\":\"看看待办\"")
                    .contains("\"modules\":[]");
        }
    }

    @Test
    void rejectsNonLoopbackIpcTargets() {
        for (String url : List.of(
                "http://192.0.2.10:18080",
                "http://localhost:18080",
                "https://127.0.0.1:18080",
                "http://user@127.0.0.1:18080",
                "http://127.0.0.1:18080/remote",
                "http://127.0.0.1:18080/v1",
                "http://127.0.0.1:18080/v1/"
        )) {
            assertThatThrownBy(() -> AiServiceClient.serviceRoot(url))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void rejectsDecisionKindsThatMixIncompatibleFields() throws Exception {
        AtomicReference<String> response = new AtomicReference<>(
                "{\"kind\":\"QUERY\",\"conversationId\":\"c1\",\"actions\":[],"
                        + "\"queries\":[" + query("q1") + "]}"
        );
        try (Server server = Server.start(
                exchange -> writeJson(exchange, 200, response.get())
        )) {
            AiServiceClient client = client(server.url(), "");
            AiContracts.ServiceTurnRequest request = new AiContracts.ServiceTurnRequest(
                    "查询",
                    null,
                    "mixed-kind-1",
                    false,
                    List.of()
            );

            assertThatThrownBy(() -> client.createTurn(request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE"));

            response.set(
                    "{\"kind\":\"ACTIONS\",\"conversationId\":\"c1\",\"actions\":["
                            + "{\"moduleId\":\"module_fs\",\"actionId\":\"fs.mkdir\","
                            + "\"arguments\":{\"path\":\"mixed\"}}],"
                            + "\"question\":{\"prompt\":\"混入问题\",\"options\":[]}}"
            );
            assertThatThrownBy(() -> client.createTurn(request))
                    .isInstanceOfSatisfying(ApiException.class, exception ->
                            assertThat(exception.code()).isEqualTo("AI_INVALID_RESPONSE"));
        }
    }

    @Test
    void totalTurnBudgetPreventsRequestsAfterDeadline() {
        AiServiceClient client = client("http://127.0.0.1:9", "");

        assertThatThrownBy(() -> client.createTurn(
                new AiContracts.ServiceTurnRequest(
                        "查询",
                        null,
                        "expired-turn",
                        false,
                        List.of()
                ),
                new AiServiceClient.TurnBudget(Instant.now().minusSeconds(1)).remaining()
        )).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.status().value()).isEqualTo(503);
            assertThat(exception.code()).isEqualTo("AI_SERVICE_UNAVAILABLE");
        });
    }

    private static AiServiceClient client(String url, String token) {
        return new AiServiceClient(
                new ObjectMapper(),
                url,
                Duration.ofSeconds(1),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                token
        );
    }

    private static String query(String suffix) {
        return "{\"moduleId\":\"module_todo\",\"queryId\":\"todo."
                + suffix + "\",\"arguments\":{}}";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Server(HttpServer server) implements AutoCloseable {
        static Server start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handler.handle(exchange));
            server.start();
            return new Server(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
