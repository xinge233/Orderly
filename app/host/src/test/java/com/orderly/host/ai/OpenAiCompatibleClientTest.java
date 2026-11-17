package com.orderly.host.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleClientTest {

    @Test
    void appendsChatCompletionsPathWithoutDuplicatingIt() {
        assertThat(OpenAiCompatibleClient.endpoint("https://api.deepseek.com").toString())
                .isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(OpenAiCompatibleClient.endpoint("https://api.example.com/v1/chat/completions").toString())
                .isEqualTo("https://api.example.com/v1/chat/completions");
    }

    @Test
    void sendsOpenAiCompatibleChatCompletionRequest() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] response = """
                    {"choices":[{"message":{"content":"{\\"kind\\":\\"QUESTION\\"}"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(new ObjectMapper());
            String content = client.complete(
                    new AiProviderConfig(
                            AiContracts.Mode.CUSTOM,
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-model",
                            "temporary-key",
                            false
                    ),
                    "system",
                    List.of(new AiChatMessage("user", "hello"))
            );

            assertThat(authorization.get()).isEqualTo("Bearer temporary-key");
            assertThat(requestBody.get()).contains("\"model\":\"test-model\"");
            assertThat(requestBody.get()).contains("\"role\":\"user\"");
            assertThat(content).isEqualTo("{\"kind\":\"QUESTION\"}");
        } finally {
            server.stop(0);
        }
    }
}
