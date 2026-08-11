package com.moyuan.modelport.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moyuan.modelport.config.GatewayProperties;
import com.moyuan.modelport.config.GatewayProperties.Protocol;
import com.moyuan.modelport.config.GatewayProperties.RoutingPolicy;
import com.moyuan.modelport.protocol.ProtocolTranslator;
import com.moyuan.modelport.routing.ModelRouter;
import com.moyuan.modelport.routing.ProviderHealthRegistry;
import com.moyuan.modelport.routing.RoutingRequestProfile;
import com.moyuan.modelport.routing.RoutingTraceStore;
import com.moyuan.modelport.security.ClientAuthFilter;
import com.moyuan.modelport.store.BudgetService;
import com.moyuan.modelport.store.IdempotencyStore;
import com.moyuan.modelport.store.UsageLedger;
import com.moyuan.modelport.web.GatewayException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

@Service
public class GatewayService {
    private final WebClient webClient;
    private final ModelRouter router;
    private final ProviderHealthRegistry health;
    private final RoutingTraceStore traces;
    private final ProtocolTranslator translator;
    private final IdempotencyStore idempotency;
    private final UsageLedger usageLedger;
    private final BudgetService budgets;
    private final ObjectMapper mapper;
    private final GatewayProperties properties;

    public GatewayService(
            WebClient webClient,
            ModelRouter router,
            ProviderHealthRegistry health,
            RoutingTraceStore traces,
            ProtocolTranslator translator,
            IdempotencyStore idempotency,
            UsageLedger usageLedger,
            BudgetService budgets,
            ObjectMapper mapper,
            GatewayProperties properties
    ) {
        this.webClient = webClient;
        this.router = router;
        this.health = health;
        this.traces = traces;
        this.translator = translator;
        this.idempotency = idempotency;
        this.usageLedger = usageLedger;
        this.budgets = budgets;
        this.mapper = mapper;
        this.properties = properties;
    }

    public Mono<ProxyResponse> exchange(
            Protocol clientProtocol,
            ObjectNode request,
            String idempotencyKey,
            ServerWebExchange exchange
    ) {
        validateRequest(request);
        String clientKeyId = exchange.getAttributeOrDefault(ClientAuthFilter.CLIENT_KEY_ID, "unknown");
        String tenantId = exchange.getAttributeOrDefault(ClientAuthFilter.TENANT_ID, "default");
        String requestId = requestId(exchange);
        long started = System.nanoTime();

        return blocking(() -> Optional.ofNullable(idempotency.find(clientKeyId, idempotencyKey, request)))
                .flatMap(cached -> cached
                        .map(value -> replay(
                                value,
                                request,
                                clientProtocol,
                                requestId,
                                clientKeyId,
                                tenantId,
                                started))
                        .orElseGet(() -> exchangeUncached(
                                request,
                                idempotencyKey,
                                clientProtocol,
                                requestId,
                                clientKeyId,
                                tenantId,
                                started,
                                exchange)));
    }

    private Mono<ProxyResponse> replay(
            IdempotencyStore.CachedResponse cached,
            ObjectNode request,
            Protocol clientProtocol,
            String requestId,
            String clientKeyId,
            String tenantId,
            long started
    ) {
        return blockingRun(() -> usageLedger.record(
                        requestId,
                        clientKeyId,
                        tenantId,
                        "idempotency-cache",
                        request.path("model").asText(),
                        request.path("model").asText(),
                        clientProtocol.name(),
                        cached.status(),
                        elapsed(started),
                        inputTokens(cached.body(), clientProtocol),
                        outputTokens(cached.body(), clientProtocol),
                        true))
                .thenReturn(new ProxyResponse(
                        cached.status(),
                        MediaType.APPLICATION_JSON,
                        cached.body(),
                        Map.of(
                                "x-modelport-route", "idempotency-cache",
                                "x-modelport-fallback-count", "0"),
                        "idempotency-cache",
                        request.path("model").asText()));
    }

    private Mono<ProxyResponse> exchangeUncached(
            ObjectNode request,
            String idempotencyKey,
            Protocol clientProtocol,
            String requestId,
            String clientKeyId,
            String tenantId,
            long started,
            ServerWebExchange exchange
    ) {
        RoutingPolicy policy = router.policy(
                exchange.getRequest().getHeaders().getFirst("x-modelport-routing-policy"));
        int estimatedInputTokens = estimateInputTokens(request);
        RoutingRequestProfile profile = RoutingRequestProfile.from(
                request, estimatedInputTokens, latencySlo(exchange), clientProtocol);
        ModelRouter.Plan plan = router.plan(
                request.path("model").asText(null), policy, profile);
        long reservationTokens = (long) estimatedInputTokens + maxOutputTokens(request);
        long monthlyLimit = monthlyLimit(clientKeyId);
        List<RoutingTraceStore.Attempt> attempts = new CopyOnWriteArrayList<>();

        return blocking(() -> budgets.reserve(
                        requestId,
                        tenantId,
                        clientKeyId,
                        monthlyLimit,
                        reservationTokens))
                .flatMap(reservation -> attempt(
                                plan,
                                0,
                                request,
                                idempotencyKey,
                                clientProtocol,
                                requestId,
                                clientKeyId,
                                tenantId,
                                started,
                                estimatedInputTokens,
                                reservation,
                                attempts)
                        .doOnSuccess(response -> traces.record(
                                requestId,
                                plan,
                                attempts,
                                response.selectedProvider(),
                                response.selectedModel(),
                                response.status() >= 200 && response.status() < 300
                                        ? "completed"
                                        : "upstream_error",
                                elapsed(started)))
                        .onErrorResume(error -> blockingRun(() -> budgets.release(reservation))
                                .onErrorResume(releaseError -> Mono.empty())
                                .then(Mono.fromRunnable(() -> traces.record(
                                        requestId,
                                        plan,
                                        attempts,
                                        null,
                                        null,
                                        "failed",
                                        elapsed(started))))
                                .then(Mono.error(mapTerminalError(error)))));
    }

    private Mono<ProxyResponse> attempt(
            ModelRouter.Plan plan,
            int index,
            ObjectNode request,
            String idempotencyKey,
            Protocol clientProtocol,
            String requestId,
            String clientKeyId,
            String tenantId,
            long started,
            int estimatedInputTokens,
            BudgetService.Reservation reservation,
            List<RoutingTraceStore.Attempt> attempts
    ) {
        ModelRouter.Route route = plan.routes().get(index);
        Protocol upstreamProtocol = route.provider().getProtocol();
        boolean streamRequested = request.path("stream").asBoolean(false);
        boolean rawStream = streamRequested && clientProtocol == upstreamProtocol;
        ObjectNode upstreamRequest = translator.request(
                clientProtocol,
                upstreamProtocol,
                request,
                route.upstreamModel());
        if (streamRequested && !rawStream) upstreamRequest.put("stream", false);
        String path = upstreamProtocol == Protocol.OPENAI
                ? "/v1/chat/completions"
                : "/v1/messages";
        long attemptStarted = System.nanoTime();

        WebClient.RequestBodySpec call = webClient.post()
                .uri(normalizeBaseUrl(route.provider().getBaseUrl()) + path)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(rawStream ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON);
        applyProviderAuth(call, route.provider());

        return call.bodyValue(upstreamRequest)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    long attemptLatency = elapsed(attemptStarted);
                    if (retryable(status) && index + 1 < plan.routes().size()) {
                        health.failure(route.provider().getId(), attemptLatency);
                        attempts.add(new RoutingTraceStore.Attempt(
                                route.provider().getId(),
                                route.upstreamModel(),
                                status,
                                attemptLatency,
                                "fallback"));
                        return response.releaseBody()
                                .then(Mono.error(RetryNextRouteException.INSTANCE));
                    }

                    if (rawStream && response.statusCode().is2xxSuccessful()) {
                        attempts.add(new RoutingTraceStore.Attempt(
                                route.provider().getId(),
                                route.upstreamModel(),
                                status,
                                attemptLatency,
                                "stream_open"));
                        Flux<DataBuffer> stream = response.bodyToFlux(DataBuffer.class)
                                .doOnComplete(() -> schedule(() -> {
                                    health.success(route.provider().getId(), elapsed(attemptStarted));
                                    budgets.settle(reservation, reservation.reservedTokens());
                                    usageLedger.record(
                                            requestId,
                                            clientKeyId,
                                            tenantId,
                                            route.provider().getId(),
                                            route.requestedModel(),
                                            route.upstreamModel(),
                                            clientProtocol.name(),
                                            status,
                                            elapsed(started),
                                            estimatedInputTokens,
                                            0,
                                            false);
                                }))
                                .doOnError(error -> schedule(() -> {
                                    health.failure(route.provider().getId(), elapsed(attemptStarted));
                                    budgets.release(reservation);
                                }))
                                .doOnCancel(() -> schedule(() -> budgets.release(reservation)));
                        return Mono.just(new ProxyResponse(
                                status,
                                MediaType.TEXT_EVENT_STREAM,
                                stream,
                                routeHeaders(plan, route, attempts),
                                route.provider().getId(),
                                route.upstreamModel()));
                    }

                    return response.bodyToMono(String.class)
                            .defaultIfEmpty("{}")
                            .flatMap(rawBody -> completeBufferedAttempt(
                                    rawBody,
                                    status,
                                    plan,
                                    route,
                                    request,
                                    idempotencyKey,
                                    clientProtocol,
                                    upstreamProtocol,
                                    streamRequested,
                                    requestId,
                                    clientKeyId,
                                    tenantId,
                                    started,
                                    reservation,
                                    attempts,
                                    attemptStarted));
                })
                .timeout(properties.getRequestTimeout())
                .onErrorResume(error -> {
                    if (error instanceof RetryNextRouteException) {
                        return attempt(
                                plan,
                                index + 1,
                                request,
                                idempotencyKey,
                                clientProtocol,
                                requestId,
                                clientKeyId,
                                tenantId,
                                started,
                                estimatedInputTokens,
                                reservation,
                                attempts);
                    }
                    long attemptLatency = elapsed(attemptStarted);
                    health.failure(route.provider().getId(), attemptLatency);
                    attempts.add(new RoutingTraceStore.Attempt(
                            route.provider().getId(),
                            route.upstreamModel(),
                            0,
                            attemptLatency,
                            error instanceof TimeoutException ? "timeout" : "transport_error"));
                    if (index + 1 < plan.routes().size()) {
                        return attempt(
                                plan,
                                index + 1,
                                request,
                                idempotencyKey,
                                clientProtocol,
                                requestId,
                                clientKeyId,
                                tenantId,
                                started,
                                estimatedInputTokens,
                                reservation,
                                attempts);
                    }
                    return Mono.error(error);
                });
    }

    private Mono<ProxyResponse> completeBufferedAttempt(
            String rawBody,
            int status,
            ModelRouter.Plan plan,
            ModelRouter.Route route,
            ObjectNode request,
            String idempotencyKey,
            Protocol clientProtocol,
            Protocol upstreamProtocol,
            boolean streamRequested,
            String requestId,
            String clientKeyId,
            String tenantId,
            long started,
            BudgetService.Reservation reservation,
            List<RoutingTraceStore.Attempt> attempts,
            long attemptStarted
    ) {
        JsonNode parsed = parseBody(rawBody);
        boolean successful = status >= 200 && status < 300;
        JsonNode clientBody = successful
                ? translator.response(
                        upstreamProtocol,
                        clientProtocol,
                        parsed,
                        route.requestedModel())
                : parsed;
        int input = inputTokens(clientBody, clientProtocol);
        int output = outputTokens(clientBody, clientProtocol);
        long actual = input + (long) output;
        if (actual == 0) actual = reservation.reservedTokens();
        long settledTokens = actual;
        long attemptLatency = elapsed(attemptStarted);

        attempts.add(new RoutingTraceStore.Attempt(
                route.provider().getId(),
                route.upstreamModel(),
                status,
                attemptLatency,
                successful ? "success" : "upstream_error"));
        if (successful) {
            health.success(route.provider().getId(), attemptLatency);
        } else if (retryable(status)) {
            health.failure(route.provider().getId(), attemptLatency);
        }

        return blockingRun(() -> {
                    usageLedger.record(
                            requestId,
                            clientKeyId,
                            tenantId,
                            route.provider().getId(),
                            route.requestedModel(),
                            route.upstreamModel(),
                            clientProtocol.name(),
                            status,
                            elapsed(started),
                            input,
                            output,
                            false);
                    if (successful) {
                        budgets.settle(reservation, settledTokens);
                        if (!streamRequested) {
                            idempotency.save(
                                    clientKeyId,
                                    idempotencyKey,
                                    request,
                                    status,
                                    clientBody);
                        }
                    } else {
                        budgets.release(reservation);
                    }
                })
                .thenReturn(new ProxyResponse(
                        status,
                        successful && streamRequested
                                ? MediaType.TEXT_EVENT_STREAM
                                : MediaType.APPLICATION_JSON,
                        successful && streamRequested
                                ? syntheticStream(clientProtocol, clientBody)
                                : clientBody,
                        routeHeaders(plan, route, attempts),
                        route.provider().getId(),
                        route.upstreamModel()));
    }

    private Map<String, String> routeHeaders(
            ModelRouter.Plan plan,
            ModelRouter.Route route,
            List<RoutingTraceStore.Attempt> attempts
    ) {
        double predictedLatency = route.evidence().getOrDefault(
                "predicted_latency_ms", 0.0);
        boolean sloMet = plan.profile().latencySloMs() == null
                || predictedLatency <= plan.profile().latencySloMs();
        return Map.of(
                "x-modelport-route", route.provider().getId() + "/" + route.upstreamModel(),
                "x-modelport-routing-policy", plan.policy().name().toLowerCase(),
                "x-modelport-route-score", String.format(java.util.Locale.ROOT, "%.6f", route.score()),
                "x-modelport-predicted-latency-ms", String.format(
                        java.util.Locale.ROOT, "%.0f", predictedLatency),
                "x-modelport-latency-slo-met", plan.profile().latencySloMs() == null
                        ? "not-requested" : Boolean.toString(sloMet),
                "x-modelport-fallback-count", Integer.toString(Math.max(0, attempts.size() - 1)));
    }

    private void validateRequest(ObjectNode request) {
        if (!request.hasNonNull("model") || request.path("model").asText().isBlank()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "model is required");
        }
        if (!request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "messages must be a non-empty array");
        }
        if (request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > properties.getMaxRequestBytes()) {
            throw new GatewayException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "request body exceeds configured limit");
        }
    }

    private Flux<ServerSentEvent<String>> syntheticStream(
            Protocol protocol,
            JsonNode response
    ) {
        if (protocol == Protocol.OPENAI) {
            ObjectNode chunk = response.deepCopy();
            chunk.put("object", "chat.completion.chunk");
            return Flux.just(
                    ServerSentEvent.builder(chunk.toString()).build(),
                    ServerSentEvent.builder("[DONE]").build());
        }
        String text = response.path("content").path(0).path("text").asText("");
        ObjectNode start = mapper.createObjectNode();
        start.put("type", "message_start");
        start.set("message", response);
        ObjectNode delta = mapper.createObjectNode();
        delta.put("type", "content_block_delta");
        delta.put("index", 0);
        delta.putObject("delta").put("type", "text_delta").put("text", text);
        return Flux.just(
                event("message_start", start),
                event("content_block_delta", delta),
                event("message_stop", mapper.createObjectNode().put("type", "message_stop")));
    }

    private ServerSentEvent<String> event(String name, JsonNode data) {
        return ServerSentEvent.<String>builder()
                .event(name)
                .data(data.toString())
                .build();
    }

    private void applyProviderAuth(
            WebClient.RequestBodySpec call,
            GatewayProperties.Provider provider
    ) {
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) return;
        if (provider.getProtocol() == Protocol.ANTHROPIC) {
            call.header("x-api-key", provider.getApiKey())
                    .header("anthropic-version", "2023-06-01");
        } else {
            call.header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.getApiKey());
        }
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private Throwable mapTerminalError(Throwable error) {
        if (error instanceof TimeoutException) {
            return new GatewayException(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "all eligible upstream providers timed out");
        }
        if (error instanceof WebClientRequestException) {
            return new GatewayException(
                    HttpStatus.BAD_GATEWAY,
                    "all eligible upstream providers were unreachable");
        }
        return error;
    }

    private static final class RetryNextRouteException extends RuntimeException {
        private static final RetryNextRouteException INSTANCE = new RetryNextRouteException();

        private RetryNextRouteException() {
            super(null, null, false, false);
        }
    }

    private String requestId(ServerWebExchange exchange) {
        String supplied = exchange.getRequest().getHeaders().getFirst("x-request-id");
        return supplied == null || supplied.isBlank()
                ? UUID.randomUUID().toString()
                : supplied;
    }

    private JsonNode parseBody(String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (Exception error) {
            return mapper.createObjectNode().put("raw", rawBody);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "provider base URL is not configured");
        }
        return baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private int inputTokens(JsonNode response, Protocol protocol) {
        return protocol == Protocol.OPENAI
                ? response.path("usage").path("prompt_tokens").asInt()
                : response.path("usage").path("input_tokens").asInt();
    }

    private int outputTokens(JsonNode response, Protocol protocol) {
        return protocol == Protocol.OPENAI
                ? response.path("usage").path("completion_tokens").asInt()
                : response.path("usage").path("output_tokens").asInt();
    }

    private int estimateInputTokens(ObjectNode request) {
        int characters = request.path("messages").toString().length()
                + request.path("system").toString().length()
                + request.path("tools").toString().length()
                + request.path("tool_choice").toString().length();
        return Math.max(1, (int) Math.ceil(characters / 3.2));
    }

    private int maxOutputTokens(ObjectNode request) {
        int value = request.has("max_completion_tokens")
                ? request.path("max_completion_tokens").asInt(1024)
                : request.path("max_tokens").asInt(1024);
        return Math.max(1, value);
    }

    private Long latencySlo(ServerWebExchange exchange) {
        String raw = exchange.getRequest().getHeaders().getFirst(
                "x-modelport-latency-slo-ms");
        if (raw == null || raw.isBlank()) return null;
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw new NumberFormatException("non-positive");
            return value;
        } catch (NumberFormatException error) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "x-modelport-latency-slo-ms must be a positive integer");
        }
    }

    private long monthlyLimit(String clientKeyId) {
        return properties.getClientKeys().stream()
                .filter(key -> key.getId().equals(clientKeyId))
                .mapToLong(GatewayProperties.ClientKey::getMonthlyTokenBudget)
                .findFirst()
                .orElse(1_000_000);
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> task) {
        return Mono.fromCallable(task).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> blockingRun(Runnable task) {
        return Mono.fromRunnable(task)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void schedule(Runnable task) {
        Schedulers.boundedElastic().schedule(task);
    }

    public record ProxyResponse(
            int status,
            MediaType contentType,
            Object body,
            Map<String, String> headers,
            String selectedProvider,
            String selectedModel
    ) {
    }
}
