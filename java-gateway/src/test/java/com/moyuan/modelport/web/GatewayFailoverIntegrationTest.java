package com.moyuan.modelport.web;

import com.moyuan.modelport.routing.ProviderHealthRegistry;
import com.moyuan.modelport.routing.RoutingTraceStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayFailoverIntegrationTest {
    private static final MockWebServer PRIMARY = server();
    private static final MockWebServer FALLBACK = server();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("modelport.failure-threshold", () -> "2");
        registry.add("modelport.circuit-open-duration", () -> "30s");

        registry.add("modelport.providers[0].id", () -> "primary");
        registry.add("modelport.providers[0].base-url", () -> PRIMARY.url("/").toString());
        registry.add("modelport.providers[0].protocol", () -> "OPENAI");
        registry.add("modelport.providers[0].enabled", () -> "true");
        registry.add("modelport.providers[0].models[0]", () -> "primary-model");
        registry.add("modelport.providers[0].quality-score", () -> "0.98");
        registry.add("modelport.providers[0].input-cost-per-million", () -> "2");
        registry.add("modelport.providers[0].output-cost-per-million", () -> "4");
        registry.add("modelport.providers[0].expected-latency-ms", () -> "500");

        registry.add("modelport.providers[1].id", () -> "fallback");
        registry.add("modelport.providers[1].base-url", () -> FALLBACK.url("/").toString());
        registry.add("modelport.providers[1].protocol", () -> "OPENAI");
        registry.add("modelport.providers[1].enabled", () -> "true");
        registry.add("modelport.providers[1].models[0]", () -> "fallback-model");
        registry.add("modelport.providers[1].quality-score", () -> "0.70");
        registry.add("modelport.providers[1].input-cost-per-million", () -> "0.1");
        registry.add("modelport.providers[1].output-cost-per-million", () -> "0.2");
        registry.add("modelport.providers[1].expected-latency-ms", () -> "200");

        registry.add("modelport.aliases[0].name", () -> "failover-default");
        registry.add("modelport.aliases[0].provider", () -> "primary");
        registry.add("modelport.aliases[0].model", () -> "primary-model");
        registry.add("modelport.aliases[1].name", () -> "failover-default");
        registry.add("modelport.aliases[1].provider", () -> "fallback");
        registry.add("modelport.aliases[1].model", () -> "fallback-model");
    }

    @Autowired WebTestClient client;
    @Autowired ProviderHealthRegistry health;
    @Autowired RoutingTraceStore traces;

    @AfterAll
    static void shutdown() throws IOException {
        PRIMARY.shutdown();
        FALLBACK.shutdown();
    }

    @Test
    void fallsBackOnRetryableResponsesAndThenBypassesAnOpenCircuit() {
        PRIMARY.enqueue(errorResponse(503));
        PRIMARY.enqueue(errorResponse(503));
        FALLBACK.enqueue(successResponse("fallback-1"));
        FALLBACK.enqueue(successResponse("fallback-2"));
        FALLBACK.enqueue(successResponse("fallback-3"));

        post("failover-1")
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-modelport-route", "fallback/fallback-model")
                .expectHeader().valueEquals("x-modelport-fallback-count", "1")
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("fallback-1");
        post("failover-2")
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-modelport-fallback-count", "1");
        post("failover-3")
                .expectStatus().isOk()
                .expectHeader().valueEquals("x-modelport-fallback-count", "0");

        assertThat(PRIMARY.getRequestCount()).isEqualTo(2);
        assertThat(FALLBACK.getRequestCount()).isEqualTo(3);
        assertThat(health.available("primary")).isFalse();
        assertThat(traces.recent()).isNotEmpty();
        assertThat(traces.recent().get(0).fallbackCount()).isZero();
        assertThat(traces.recent().get(1).fallbackCount()).isEqualTo(1);
        assertThat(traces.recent().get(1).attempts()).hasSize(2);
    }

    private WebTestClient.ResponseSpec post(String idempotencyKey) {
        return client.post()
                .uri("/v1/chat/completions")
                .header("x-api-key", "dev-client-key")
                .header("Idempotency-Key", idempotencyKey)
                .header("x-request-id", "request-" + idempotencyKey)
                .header("x-modelport-routing-policy", "quality")
                .bodyValue(Map.of(
                        "model", "failover-default",
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", "hello"))))
                .exchange();
    }

    private static MockWebServer server() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static MockResponse errorResponse(int status) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setResponseCode(status)
                .setBody("{\"error\":{\"message\":\"retry\"}}");
    }

    private static MockResponse successResponse(String content) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200)
                .setBody("""
                        {
                          "id":"chatcmpl-fallback",
                          "object":"chat.completion",
                          "model":"fallback-model",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                          "usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}
                        }
                        """.formatted(content));
    }
}
