package com.moyuan.modelport.routing;

import com.moyuan.modelport.config.GatewayProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProviderHealthRegistry {
    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public ProviderHealthRegistry(GatewayProperties properties) {
        this.properties = properties;
    }

    public boolean available(String providerId) {
        return state(providerId).available();
    }

    public Snapshot snapshot(String providerId, long configuredLatencyMs) {
        return state(providerId).snapshot(configuredLatencyMs);
    }

    public void success(String providerId, long latencyMs) {
        state(providerId).success(latencyMs);
    }

    public void failure(String providerId, long latencyMs) {
        state(providerId).failure(
                latencyMs,
                Math.max(1, properties.getFailureThreshold()),
                properties.getCircuitOpenDuration().toMillis());
    }

    public Map<String, Snapshot> snapshots() {
        Map<String, Snapshot> output = new LinkedHashMap<>();
        properties.getProviders().forEach(provider -> output.put(
                provider.getId(),
                snapshot(provider.getId(), provider.getExpectedLatencyMs())));
        return Map.copyOf(output);
    }

    private State state(String providerId) {
        return states.computeIfAbsent(providerId, ignored -> new State());
    }

    private static final class State {
        private long successes;
        private long failures;
        private int consecutiveFailures;
        private double ewmaLatencyMs;
        private long circuitOpenUntilEpochMs;

        synchronized boolean available() {
            return System.currentTimeMillis() >= circuitOpenUntilEpochMs;
        }

        synchronized void success(long latencyMs) {
            successes++;
            consecutiveFailures = 0;
            circuitOpenUntilEpochMs = 0;
            ewmaLatencyMs = ewmaLatencyMs == 0
                    ? latencyMs
                    : ewmaLatencyMs * 0.8 + latencyMs * 0.2;
        }

        synchronized void failure(long latencyMs, int threshold, long openDurationMs) {
            failures++;
            consecutiveFailures++;
            if (latencyMs > 0) {
                ewmaLatencyMs = ewmaLatencyMs == 0
                        ? latencyMs
                        : ewmaLatencyMs * 0.8 + latencyMs * 0.2;
            }
            if (consecutiveFailures >= threshold) {
                circuitOpenUntilEpochMs = System.currentTimeMillis() + openDurationMs;
            }
        }

        synchronized Snapshot snapshot(long configuredLatencyMs) {
            double reliability = (successes + 1.0) / (successes + failures + 2.0);
            long observedLatency = ewmaLatencyMs == 0
                    ? configuredLatencyMs
                    : Math.max(1, Math.round(ewmaLatencyMs));
            boolean available = available();
            Instant circuitOpenUntil = circuitOpenUntilEpochMs == 0
                    ? null
                    : Instant.ofEpochMilli(circuitOpenUntilEpochMs);
            return new Snapshot(
                    available ? "available" : "circuit_open",
                    available,
                    successes,
                    failures,
                    consecutiveFailures,
                    reliability,
                    observedLatency,
                    circuitOpenUntil);
        }
    }

    public record Snapshot(
            String status,
            boolean available,
            long successes,
            long failures,
            int consecutiveFailures,
            double reliability,
            long observedLatencyMs,
            Instant circuitOpenUntil
    ) {
    }
}
