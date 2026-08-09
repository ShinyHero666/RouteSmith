package com.moyuan.modelport.web;

import com.moyuan.modelport.config.GatewayProperties;
import com.moyuan.modelport.routing.ModelRouter;
import com.moyuan.modelport.routing.ProviderHealthRegistry;
import com.moyuan.modelport.routing.RoutingTraceStore;
import com.moyuan.modelport.store.BudgetService;
import com.moyuan.modelport.store.UsageLedger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final GatewayProperties properties;
    private final ModelRouter router;
    private final ProviderHealthRegistry health;
    private final RoutingTraceStore traces;
    private final UsageLedger usage;
    private final BudgetService budgets;

    public AdminController(
            GatewayProperties properties,
            ModelRouter router,
            ProviderHealthRegistry health,
            RoutingTraceStore traces,
            UsageLedger usage,
            BudgetService budgets
    ) {
        this.properties = properties;
        this.router = router;
        this.health = health;
        this.traces = traces;
        this.usage = usage;
        this.budgets = budgets;
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return properties.getProviders().stream().map(provider -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", provider.getId());
            view.put("baseUrl", provider.getBaseUrl());
            view.put("protocol", provider.getProtocol());
            view.put("enabled", provider.isEnabled());
            view.put("models", provider.getModels());
            view.put("qualityScore", provider.getQualityScore());
            view.put("inputCostPerMillion", provider.getInputCostPerMillion());
            view.put("outputCostPerMillion", provider.getOutputCostPerMillion());
            view.put("expectedLatencyMs", provider.getExpectedLatencyMs());
            view.put(
                    "credentialConfigured",
                    provider.getApiKey() != null && !provider.getApiKey().isBlank());
            view.put(
                    "health",
                    health.snapshot(provider.getId(), provider.getExpectedLatencyMs()));
            return Map.copyOf(view);
        }).toList();
    }

    @GetMapping("/aliases")
    public Map<String, Object> aliases() {
        return Map.of("models", router.models());
    }

    @GetMapping("/routing-decisions")
    public Map<String, Object> routingDecisions() {
        return Map.of("data", traces.recent(), "count", traces.recent().size());
    }

    @GetMapping("/logs")
    public Map<String, Object> logs() {
        return Map.of("data", usage.recent(), "count", usage.recent().size());
    }

    @GetMapping("/budgets")
    public Map<String, Object> budgets() {
        return Map.of(
                "accounts", budgets.accounts(),
                "reservations", budgets.recentReservations());
    }
}
