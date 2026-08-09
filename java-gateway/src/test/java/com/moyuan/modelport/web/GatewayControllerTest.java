package com.moyuan.modelport.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest
@AutoConfigureWebTestClient
class GatewayControllerTest {
    @Autowired WebTestClient client;

    @Test
    void protectsClientApiAndListsConfiguredAliasesForValidKey() {
        client.get().uri("/v1/models")
                .exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/v1/models")
                .header("x-api-key", "dev-client-key")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].id").isEqualTo("qwen-default");
    }
}
