package com.moyuan.modelport.web;

import com.moyuan.modelport.routing.RoutingTraceStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
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
class GatewayTransportFailureIntegrationTest {
    private static final MockWebServer PRIMARY = server();
    private static final MockWebServer FALLBACK = server();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("modelport.request-timeout", () -> "2s");
        registry.add("modelport.failure-threshold", () -> "2");

        registry.add("modelport.providers[0].id", () -> "transport-primary");
        registry.add("modelport.providers[0].base-url", () -> PRIMARY.url("/").toString());
        registry.add("modelport.providers[0].protocol", () -> "OPENAI");
        registry.add("modelport.providers[0].enabled", () -> "true");
        registry.add("modelport.providers[0].models[0]", () -> "primary-model");
        registry.add("modelport.providers[0].quality-score", () -> "0.98");
        registry.add("modelport.providers[0].expected-latency-ms", () -> "100");

        registry.add("modelport.providers[1].id", () -> "transport-fallback");
        registry.add("modelport.providers[1].base-url", () -> FALLBACK.url("/").toString());
        registry.add("modelport.providers[1].protocol", () -> "OPENAI");
        registry.add("modelport.providers[1].enabled", () -> "true");
        registry.add("modelport.providers[1].models[0]", () -> "fallback-model");
        registry.add("modelport.providers[1].quality-score", () -> "0.70");
        registry.add("modelport.providers[1].expected-latency-ms", () -> "200");

        registry.add("modelport.aliases[0].name", () -> "transport-default");
        registry.add("modelport.aliases[0].provider", () -> "transport-primary");
        registry.add("modelport.aliases[0].model", () -> "primary-model");
        registry.add("modelport.aliases[1].name", () -> "transport-default");
        registry.add("modelport.aliases[1].provider", () -> "transport-fallback");
        registry.add("modelport.aliases[1].model", () -> "fallback-model");
    }

    @Autowired WebTestClient client;
    @Autowired RoutingTraceStore traces;

    @AfterAll
    static void shutdown() throws IOException {
        PRIMARY.shutdown();
        FALLBACK.shutdown();
    }

    @Test
    void attemptsEachCandidateOnceWhenFallbackTransportFails() {
        PRIMARY.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setResponseCode(503)
                .setBody("{\"error\":{\"message\":\"retry\"}}"));
        FALLBACK.enqueue(new MockResponse()
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        client.post()
                .uri("/v1/chat/completions")
                .header("x-api-key", "dev-client-key")
                .header("x-request-id", "request-cascading-failure")
                .header("x-modelport-routing-policy", "quality")
                .bodyValue(Map.of(
                        "model", "transport-default",
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", "hello"))))
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.error.message")
                .isEqualTo("all eligible upstream providers were unreachable");

        assertThat(PRIMARY.getRequestCount()).isEqualTo(1);
        assertThat(FALLBACK.getRequestCount()).isEqualTo(1);
        RoutingTraceStore.Trace trace = traces.recent().stream()
                .filter(item -> item.requestId().equals("request-cascading-failure"))
                .findFirst()
                .orElseThrow();
        assertThat(trace.attempts()).hasSize(2);
        assertThat(trace.attempts().get(0).outcome()).isEqualTo("fallback");
        assertThat(trace.attempts().get(1).outcome()).isEqualTo("transport_error");
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
}
