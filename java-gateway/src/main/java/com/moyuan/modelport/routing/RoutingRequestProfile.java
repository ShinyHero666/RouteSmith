package com.moyuan.modelport.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.modelport.config.GatewayProperties.Protocol;

/** Request features that affect provider eligibility, cost, and latency. */
public record RoutingRequestProfile(
        int estimatedInputTokens,
        int maxOutputTokens,
        int toolCount,
        boolean streaming,
        Long latencySloMs,
        Protocol clientProtocol
) {
    public RoutingRequestProfile {
        if (estimatedInputTokens <= 0) throw new IllegalArgumentException("estimatedInputTokens must be positive");
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
        if (toolCount < 0) throw new IllegalArgumentException("toolCount must not be negative");
        if (latencySloMs != null && latencySloMs <= 0) {
            throw new IllegalArgumentException("latencySloMs must be positive");
        }
        if (clientProtocol == null) throw new IllegalArgumentException("clientProtocol is required");
    }

    public RoutingRequestProfile(
            int estimatedInputTokens,
            int maxOutputTokens,
            int toolCount,
            boolean streaming,
            Long latencySloMs
    ) {
        this(estimatedInputTokens, maxOutputTokens, toolCount,
                streaming, latencySloMs, Protocol.OPENAI);
    }

    public static RoutingRequestProfile from(
            ObjectNode request,
            int estimatedInputTokens,
            Long latencySloMs,
            Protocol clientProtocol
    ) {
        int maxOutputTokens = request.has("max_completion_tokens")
                ? request.path("max_completion_tokens").asInt(1024)
                : request.path("max_tokens").asInt(1024);
        JsonNode tools = request.path("tools");
        return new RoutingRequestProfile(
                estimatedInputTokens,
                Math.max(1, maxOutputTokens),
                tools.isArray() ? tools.size() : 0,
                request.path("stream").asBoolean(false),
                latencySloMs,
                clientProtocol);
    }

    public static RoutingRequestProfile defaults() {
        return new RoutingRequestProfile(512, 256, 0, false, null, Protocol.OPENAI);
    }

    public long totalTokenUpperBound() {
        return estimatedInputTokens + (long) maxOutputTokens;
    }

    public boolean requiresTools() {
        return toolCount > 0;
    }
}
