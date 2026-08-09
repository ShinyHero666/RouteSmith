package com.moyuan.modelport.routing;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

@Component
public class RoutingTraceStore {
    private static final int MAX_TRACES = 200;
    private final Deque<Trace> traces = new ArrayDeque<>();

    public synchronized void record(
            String requestId,
            ModelRouter.Plan plan,
            List<Attempt> attempts,
            String selectedProvider,
            String selectedModel,
            String status,
            long latencyMs
    ) {
        List<Candidate> candidates = plan.routes().stream()
                .map(route -> new Candidate(
                        route.provider().getId(),
                        route.upstreamModel(),
                        route.score(),
                        route.evidence()))
                .toList();
        long fallbackCount = Math.max(0, attempts.size() - 1L);
        traces.addFirst(new Trace(
                requestId,
                plan.requestedModel(),
                plan.policy().name().toLowerCase(),
                candidates,
                List.copyOf(attempts),
                selectedProvider,
                selectedModel,
                fallbackCount,
                status,
                latencyMs,
                Instant.now()));
        while (traces.size() > MAX_TRACES) traces.removeLast();
    }

    public synchronized List<Trace> recent() {
        return List.copyOf(new ArrayList<>(traces));
    }

    public record Candidate(
            String provider,
            String model,
            double score,
            Map<String, Double> evidence
    ) {
    }

    public record Attempt(
            String provider,
            String model,
            int status,
            long latencyMs,
            String outcome
    ) {
    }

    public record Trace(
            String requestId,
            String requestedModel,
            String policy,
            List<Candidate> candidates,
            List<Attempt> attempts,
            String selectedProvider,
            String selectedModel,
            long fallbackCount,
            String status,
            long latencyMs,
            Instant createdAt
    ) {
    }
}
