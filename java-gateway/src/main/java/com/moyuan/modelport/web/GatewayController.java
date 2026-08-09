package com.moyuan.modelport.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.modelport.config.GatewayProperties.Protocol;
import com.moyuan.modelport.routing.ModelRouter;
import com.moyuan.modelport.service.GatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
public class GatewayController {
    private final GatewayService gateway;
    private final ModelRouter router;

    public GatewayController(GatewayService gateway, ModelRouter router) {
        this.gateway = gateway;
        this.router = router;
    }

    @GetMapping({"/livez", "/health"})
    public Map<String, Object> live() {
        return Map.of(
                "status", "ok",
                "service", "modelport-java",
                "time", Instant.now());
    }

    @GetMapping("/readyz")
    public Map<String, Object> ready() {
        return Map.of("status", "ready", "routes", router.models().size());
    }

    @GetMapping("/v1/models")
    public Map<String, Object> models() {
        return Map.of("object", "list", "data", router.models());
    }

    @PostMapping(
            path = "/v1/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> chatCompletions(
            @RequestBody ObjectNode request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            ServerWebExchange exchange
    ) {
        return proxy(Protocol.OPENAI, request, idempotencyKey, exchange);
    }

    @PostMapping(
            path = "/v1/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> messages(
            @RequestBody ObjectNode request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            ServerWebExchange exchange
    ) {
        return proxy(Protocol.ANTHROPIC, request, idempotencyKey, exchange);
    }

    @PostMapping(
            path = "/v1/messages/count_tokens",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> countTokens(@RequestBody ObjectNode request) {
        int characters = request.path("messages").toString().length()
                + request.path("system").asText("").length();
        return Map.of(
                "input_tokens",
                Math.max(1, (int) Math.ceil(characters / 3.2)));
    }

    @GetMapping("/v1/effective-policy")
    public Map<String, Object> effectivePolicy(ServerWebExchange exchange) {
        return Map.of(
                "tenant", exchange.getAttributeOrDefault("modelport.tenantId", "default"),
                "routing", "multi_factor_with_health_fallback",
                "policies", new String[]{"balanced", "quality", "economy", "latency"},
                "streaming", true,
                "idempotency", true);
    }

    private Mono<ResponseEntity<?>> proxy(
            Protocol protocol,
            ObjectNode request,
            String idempotencyKey,
            ServerWebExchange exchange
    ) {
        return gateway.exchange(protocol, request, idempotencyKey, exchange)
                .map(response -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity
                            .status(response.status())
                            .contentType(response.contentType())
                            .header(
                                    "x-modelport-protocol",
                                    protocol.name().toLowerCase());
                    response.headers().forEach(builder::header);
                    return builder.body(response.body());
                });
    }
}
