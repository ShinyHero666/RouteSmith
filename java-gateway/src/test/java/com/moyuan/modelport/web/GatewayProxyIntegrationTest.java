package com.moyuan.modelport.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.moyuan.modelport.store.BudgetService;
import com.moyuan.modelport.store.UsageLedger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayProxyIntegrationTest {
    private static final MockWebServer UPSTREAM = new MockWebServer();

    static {
        try {
            UPSTREAM.start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("modelport.providers[0].id", () -> "local-openai");
        registry.add("modelport.providers[0].base-url", () -> UPSTREAM.url("/").toString());
        registry.add("modelport.providers[0].protocol", () -> "OPENAI");
        registry.add("modelport.providers[0].enabled", () -> "true");
        registry.add("modelport.providers[0].models[0]", () -> "qwen-local");
        registry.add("modelport.aliases[0].name", () -> "qwen-default");
        registry.add("modelport.aliases[0].provider", () -> "local-openai");
        registry.add("modelport.aliases[0].model", () -> "qwen-local");
    }

    @Autowired WebTestClient client;
    @Autowired ObjectMapper mapper;
    @Autowired BudgetService budgets;
    @Autowired UsageLedger usage;

    @AfterAll
    static void shutdown() throws IOException {
        UPSTREAM.shutdown();
    }

    @Test
    void rewritesAliasAndDoesNotCallProviderTwiceForAnIdempotentReplay() throws Exception {
        UPSTREAM.enqueue(jsonResponse("""
                {
                  "id":"chatcmpl-1",
                  "object":"chat.completion",
                  "model":"qwen-local",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}
                }
                """));
        Map<String, Object> request = Map.of(
                "model", "qwen-default",
                "messages", List.of(Map.of("role", "user", "content", "hello"))
        );

        postOpenAi("same-turn", request)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.model").isEqualTo("qwen-local")
                .jsonPath("$.choices[0].message.content").isEqualTo("ok");

        var reservation = budgets.recentReservations().stream()
                .filter(item -> item.requestId().equals("request-same-turn"))
                .findFirst()
                .orElseThrow();
        assertThat(reservation.status()).isEqualTo("SETTLED");
        assertThat(reservation.actualTokens()).isEqualTo(10);
        int reservationsAfterFirstRequest = budgets.recentReservations().size();

        RecordedRequest upstream = UPSTREAM.takeRequest(2, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        assertThat(upstream.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(mapper.readTree(upstream.getBody().readUtf8()).path("model").asText()).isEqualTo("qwen-local");
        int callsAfterFirstRequest = UPSTREAM.getRequestCount();

        postOpenAi("same-turn", request)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("ok");

        assertThat(UPSTREAM.getRequestCount()).isEqualTo(callsAfterFirstRequest);
        assertThat(budgets.recentReservations()).hasSize(reservationsAfterFirstRequest);
        assertThat(usage.recent().stream()
                .filter(record -> record.requestId().equals("request-same-turn"))
                .toList()).hasSize(2)
                .anySatisfy(record -> assertThat(record.idempotentReplay()).isTrue());
    }

    @Test
    void oneHundredIdenticalRequestsCallTheProviderOnlyOnce() throws Exception {
        UPSTREAM.enqueue(jsonResponse("""
                {
                  "id":"chatcmpl-bulk-replay",
                  "object":"chat.completion",
                  "model":"qwen-local",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"cached"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}
                }
                """));
        Map<String, Object> request = Map.of(
                "model", "qwen-default",
                "messages", List.of(Map.of("role", "user", "content", "repeatable request"))
        );
        int callsBefore = UPSTREAM.getRequestCount();

        postOpenAi("bulk-replay-100", request)
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("cached");
        RecordedRequest upstream = UPSTREAM.takeRequest(2, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();

        for (int replay = 1; replay < 100; replay++) {
            postOpenAi("bulk-replay-100", request)
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.choices[0].message.content").isEqualTo("cached");
        }

        assertThat(UPSTREAM.getRequestCount() - callsBefore).isEqualTo(1);
        assertThat(usage.recent().stream()
                .filter(record -> record.requestId().equals("request-bulk-replay-100"))
                .filter(UsageLedger.UsageRecord::idempotentReplay)
                .count()).isEqualTo(99);
    }

    @Test
    void translatesAnthropicClientRequestThroughAnOpenAiProvider() throws Exception {
        UPSTREAM.enqueue(jsonResponse("""
                {
                  "id":"chatcmpl-2",
                  "object":"chat.completion",
                  "model":"qwen-local",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"translated"},"finish_reason":"stop"}],
                  "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}
                }
                """));

        client.post().uri("/v1/messages")
                .header("x-api-key", "dev-client-key")
                .header("Idempotency-Key", "anthropic-turn")
                .bodyValue(Map.of(
                        "model", "qwen-default",
                        "max_tokens", 256,
                        "messages", List.of(Map.of("role", "user", "content", "hello"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.type").isEqualTo("message")
                .jsonPath("$.model").isEqualTo("qwen-default")
                .jsonPath("$.content[0].text").isEqualTo("translated")
                .jsonPath("$.usage.input_tokens").isEqualTo(12);

        RecordedRequest upstream = UPSTREAM.takeRequest(2, TimeUnit.SECONDS);
        assertThat(upstream).isNotNull();
        assertThat(upstream.getPath()).isEqualTo("/v1/chat/completions");
        var upstreamBody = mapper.readTree(upstream.getBody().readUtf8());
        assertThat(upstreamBody.path("model").asText()).isEqualTo("qwen-local");
        assertThat(upstreamBody.path("messages").path(0).path("content").asText()).isEqualTo("hello");
    }

    private WebTestClient.ResponseSpec postOpenAi(String idempotencyKey, Map<String, Object> request) {
        return client.post().uri("/v1/chat/completions")
                .header("x-api-key", "dev-client-key")
                .header("Idempotency-Key", idempotencyKey)
                .header("x-request-id", "request-" + idempotencyKey)
                .bodyValue(request)
                .exchange();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200)
                .setBody(body);
    }
}
