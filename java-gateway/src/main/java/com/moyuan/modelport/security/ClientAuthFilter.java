package com.moyuan.modelport.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moyuan.modelport.config.GatewayProperties;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
public class ClientAuthFilter implements WebFilter, Ordered {
    public static final String CLIENT_KEY_ID = "modelport.clientKeyId";
    public static final String TENANT_ID = "modelport.tenantId";

    private final GatewayProperties properties;
    private final RequestRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ClientAuthFilter(
            GatewayProperties properties,
            RequestRateLimiter rateLimiter,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/") && !path.startsWith("/admin/")) return chain.filter(exchange);

        String supplied = credential(exchange);
        GatewayProperties.ClientKey key = properties.getClientKeys().stream()
                .filter(candidate -> constantEquals(candidate.getSecret(), supplied))
                .findFirst()
                .orElse(null);
        if (key == null) return error(exchange, HttpStatus.UNAUTHORIZED, "invalid_api_key", "invalid API key");
        if (!rateLimiter.allow(key.getId(), key.getRequestsPerMinute())) {
            exchange.getResponse().getHeaders().set("Retry-After", "60");
            return error(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", "request rate limit exceeded");
        }
        exchange.getAttributes().put(CLIENT_KEY_ID, key.getId());
        exchange.getAttributes().put(TENANT_ID, key.getTenant());
        return chain.filter(exchange);
    }

    private String credential(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return "";
    }

    private boolean constantEquals(String expected, String supplied) {
        if (expected == null || expected.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Void> error(ServerWebExchange exchange, HttpStatus status, String type, String message) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(Map.of(
                    "error", Map.of("type", type, "message", message)
            ));
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            exchange.getResponse().getHeaders().setCacheControl("no-store");
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception error) {
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
