package com.moyuan.modelport.routing;

import com.moyuan.modelport.config.GatewayProperties;
import com.moyuan.modelport.config.GatewayProperties.RoutingPolicy;
import com.moyuan.modelport.web.GatewayException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ModelRouter {
    private final GatewayProperties properties;
    private final ProviderHealthRegistry health;

    public ModelRouter(GatewayProperties properties, ProviderHealthRegistry health) {
        this.properties = properties;
        this.health = health;
    }

    public Route resolve(String requestedModel) {
        return plan(requestedModel, properties.getDefaultRoutingPolicy()).routes().get(0);
    }

    public Plan plan(String requestedModel, RoutingPolicy policy) {
        return plan(requestedModel, policy, RoutingRequestProfile.defaults());
    }

    public Plan plan(
            String requestedModel,
            RoutingPolicy policy,
            RoutingRequestProfile profile
    ) {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "model is required");
        }

        List<Target> targets = aliasTargets(requestedModel);
        if (targets.isEmpty()) targets = directTargets(requestedModel);
        if (targets.isEmpty()) {
            throw new GatewayException(
                    HttpStatus.NOT_FOUND,
                    "no enabled provider route for model: " + requestedModel);
        }

        Map<String, Target> deduplicated = new LinkedHashMap<>();
        targets.forEach(target -> deduplicated.putIfAbsent(
                target.provider().getId() + "/" + target.upstreamModel(),
                target));
        List<Target> available = deduplicated.values().stream()
                .filter(target -> health.available(target.provider().getId()))
                .toList();
        if (available.isEmpty()) {
            throw new GatewayException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "all provider circuits are open for model: " + requestedModel);
        }

        List<Target> eligible = available.stream()
                .filter(target -> supports(target.provider(), profile))
                .toList();
        if (eligible.isEmpty()) {
            throw new GatewayException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "no provider route satisfies the request capabilities or context window");
        }

        List<Evaluation> evaluations = eligible.stream()
                .map(target -> evaluate(target, profile))
                .toList();
        double minimumCost = evaluations.stream()
                .mapToDouble(Evaluation::expectedCostUsd).min().orElse(0);
        double minimumLatency = evaluations.stream()
                .mapToDouble(Evaluation::predictedLatencyMs).min().orElse(1);

        List<Route> routes = evaluations.stream()
                .map(item -> score(
                        requestedModel, item, policy, profile,
                        minimumCost, minimumLatency))
                .sorted(Comparator
                        .comparingDouble((Route route) ->
                                route.evidence().getOrDefault("slo_met", 1.0))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(Route::score).reversed())
                        .thenComparing(route -> route.provider().getId()))
                .toList();
        return new Plan(requestedModel, policy, profile, routes);
    }

    public RoutingPolicy policy(String requested) {
        if (requested == null || requested.isBlank()) {
            return properties.getDefaultRoutingPolicy();
        }
        try {
            return RoutingPolicy.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "unsupported routing policy: " + requested);
        }
    }

    public List<Map<String, Object>> models() {
        Map<String, ModelViewBuilder> models = new LinkedHashMap<>();
        properties.getAliases().forEach(alias -> {
            GatewayProperties.Provider provider = providerOrNull(alias.getProvider());
            if (provider != null) {
                models.computeIfAbsent(alias.getName(), ModelViewBuilder::new)
                        .add(provider.getId(), alias.getModel());
            }
        });
        properties.getProviders().stream()
                .filter(GatewayProperties.Provider::isEnabled)
                .forEach(provider -> provider.getModels().forEach(model ->
                        models.computeIfAbsent(model, ModelViewBuilder::new)
                                .add(provider.getId(), model)));
        return models.values().stream().map(ModelViewBuilder::freeze).toList();
    }

    private List<Target> aliasTargets(String requestedModel) {
        return properties.getAliases().stream()
                .filter(alias -> requestedModel.equals(alias.getName()))
                .map(alias -> {
                    GatewayProperties.Provider provider = providerOrNull(alias.getProvider());
                    return provider == null ? null : new Target(alias.getModel(), provider);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private List<Target> directTargets(String requestedModel) {
        return properties.getProviders().stream()
                .filter(GatewayProperties.Provider::isEnabled)
                .filter(provider -> provider.getModels().contains(requestedModel))
                .map(provider -> new Target(requestedModel, provider))
                .toList();
    }

    private Route score(
            String requestedModel,
            Evaluation evaluation,
            RoutingPolicy policy,
            RoutingRequestProfile profile,
            double minimumCost,
            double minimumLatency
    ) {
        Target target = evaluation.target();
        GatewayProperties.Provider provider = target.provider();
        double quality = clamp(provider.getQualityScore());
        double cost = relativeUtility(minimumCost, evaluation.expectedCostUsd());
        double latency = relativeUtility(minimumLatency, evaluation.predictedLatencyMs());
        double reliability = clamp(evaluation.reliability());

        double score = switch (policy) {
            case QUALITY -> quality * 0.70 + reliability * 0.20 + latency * 0.10;
            case ECONOMY -> cost * 0.70 + reliability * 0.20 + quality * 0.10;
            case LATENCY -> latency * 0.70 + reliability * 0.20 + quality * 0.10;
            case BALANCED -> quality * 0.35 + cost * 0.25
                    + latency * 0.20 + reliability * 0.20;
        };

        Map<String, Double> evidence = new LinkedHashMap<>();
        evidence.put("quality", quality);
        evidence.put("cost", cost);
        evidence.put("latency", latency);
        evidence.put("reliability", reliability);
        evidence.put("estimated_cost_usd", evaluation.expectedCostUsd());
        evidence.put("predicted_latency_ms", evaluation.predictedLatencyMs());
        evidence.put("context_utilization", evaluation.contextUtilization());
        evidence.put("slo_met", profile.latencySloMs() == null
                || evaluation.predictedLatencyMs() <= profile.latencySloMs() ? 1.0 : 0.0);
        return new Route(
                requestedModel,
                target.upstreamModel(),
                provider,
                policy,
                score,
                Map.copyOf(evidence));
    }

    private Evaluation evaluate(Target target, RoutingRequestProfile profile) {
        GatewayProperties.Provider provider = target.provider();
        ProviderHealthRegistry.Snapshot snapshot = health.snapshot(
                provider.getId(), provider.getExpectedLatencyMs());
        double expectedCostUsd = (
                profile.estimatedInputTokens() * provider.getInputCostPerMillion()
                        + profile.maxOutputTokens() * provider.getOutputCostPerMillion())
                / 1_000_000.0;
        double predictedLatencyMs = predictedLatency(provider, snapshot, profile);
        double contextUtilization = profile.totalTokenUpperBound()
                / (double) Math.max(1, provider.getMaxContextTokens());
        return new Evaluation(
                target, expectedCostUsd, predictedLatencyMs,
                contextUtilization, snapshot.reliability());
    }

    private double predictedLatency(
            GatewayProperties.Provider provider,
            ProviderHealthRegistry.Snapshot snapshot,
            RoutingRequestProfile profile
    ) {
        double prefillRate = Math.max(1, provider.getPrefillTokensPerSecond());
        double decodeRate = Math.max(1, provider.getDecodeTokensPerSecond());
        double referenceWorkMs = 250
                + 512_000.0 / prefillRate
                + 256_000.0 / decodeRate;
        boolean nativeStream = profile.streaming()
                && profile.clientProtocol() == provider.getProtocol();
        double requestWorkMs = 250
                + profile.estimatedInputTokens() * 1_000.0 / prefillRate
                + (nativeStream
                        ? 0
                        : profile.maxOutputTokens() * 1_000.0 / decodeRate)
                + (profile.requiresTools() ? provider.getToolCallPenaltyMs() : 0);
        double requestFactor = Math.max(
                0.35, Math.min(6.0, requestWorkMs / referenceWorkMs));
        return Math.max(1, snapshot.observedLatencyMs() * requestFactor);
    }

    private boolean supports(
            GatewayProperties.Provider provider,
            RoutingRequestProfile profile
    ) {
        if (profile.totalTokenUpperBound() > provider.getMaxContextTokens()) return false;
        if (profile.requiresTools() && !provider.isSupportsTools()) return false;
        boolean nativeStream = profile.streaming()
                && profile.clientProtocol() == provider.getProtocol();
        return !nativeStream || provider.isSupportsStreaming();
    }

    private static double relativeUtility(double minimum, double value) {
        if (value <= 0) return 1;
        if (minimum <= 0) return 0;
        return clamp(minimum / value);
    }

    private GatewayProperties.Provider providerOrNull(String id) {
        if (id == null) return null;
        return properties.getProviders().stream()
                .filter(candidate -> id.equals(candidate.getId()))
                .filter(GatewayProperties.Provider::isEnabled)
                .findFirst()
                .orElse(null);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record Target(String upstreamModel, GatewayProperties.Provider provider) {
    }

    private record Evaluation(
            Target target,
            double expectedCostUsd,
            double predictedLatencyMs,
            double contextUtilization,
            double reliability
    ) {
    }

    public record Plan(
            String requestedModel,
            RoutingPolicy policy,
            RoutingRequestProfile profile,
            List<Route> routes
    ) {
    }

    public record Route(
            String requestedModel,
            String upstreamModel,
            GatewayProperties.Provider provider,
            RoutingPolicy policy,
            double score,
            Map<String, Double> evidence
    ) {
    }

    private static final class ModelViewBuilder {
        private final String exposed;
        private final Set<String> providers = new LinkedHashSet<>();
        private final List<String> upstreamModels = new ArrayList<>();

        private ModelViewBuilder(String exposed) {
            this.exposed = exposed;
        }

        private void add(String provider, String upstreamModel) {
            providers.add(provider);
            upstreamModels.add(upstreamModel);
        }

        private Map<String, Object> freeze() {
            String firstProvider = providers.iterator().next();
            return Map.of(
                    "id", exposed,
                    "object", "model",
                    "owned_by", "modelport",
                    "provider", firstProvider,
                    "upstream_model", upstreamModels.get(0),
                    "routing_candidates", List.copyOf(providers)
            );
        }
    }
}
