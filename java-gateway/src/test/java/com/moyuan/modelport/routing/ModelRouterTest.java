package com.moyuan.modelport.routing;

import com.moyuan.modelport.config.GatewayProperties;
import com.moyuan.modelport.config.GatewayProperties.Protocol;
import com.moyuan.modelport.config.GatewayProperties.RoutingPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRouterTest {
    @Test
    void selectsDifferentProvidersForQualityEconomyAndLatencyPolicies() {
        GatewayProperties properties = properties();
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        assertThat(router.plan("smart", RoutingPolicy.QUALITY)
                .routes().get(0).provider().getId()).isEqualTo("quality");
        assertThat(router.plan("smart", RoutingPolicy.ECONOMY)
                .routes().get(0).provider().getId()).isEqualTo("economy");
        assertThat(router.plan("smart", RoutingPolicy.LATENCY)
                .routes().get(0).provider().getId()).isEqualTo("economy");
    }

    @Test
    void filtersProvidersThatCannotServeTheRequestCapabilities() {
        GatewayProperties properties = properties();
        properties.getProviders().get(0).setSupportsTools(false);
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        RoutingRequestProfile profile = new RoutingRequestProfile(
                2_000, 200, 1, false, null);

        assertThat(router.plan("smart", RoutingPolicy.QUALITY, profile).routes())
                .extracting(route -> route.provider().getId())
                .containsExactly("economy");
    }

    @Test
    void usesPromptSizeAndProviderThroughputForLatencyRouting() {
        GatewayProperties properties = properties();
        GatewayProperties.Provider quality = properties.getProviders().get(0);
        GatewayProperties.Provider economy = properties.getProviders().get(1);
        quality.setExpectedLatencyMs(500);
        economy.setExpectedLatencyMs(500);
        quality.setPrefillTokensPerSecond(100);
        economy.setPrefillTokensPerSecond(5_000);
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        RoutingRequestProfile profile = new RoutingRequestProfile(
                20_000, 128, 0, true, null);
        ModelRouter.Route selected = router.plan(
                "smart", RoutingPolicy.LATENCY, profile).routes().get(0);

        assertThat(selected.provider().getId()).isEqualTo("economy");
        assertThat(selected.evidence()).containsKeys(
                "predicted_latency_ms",
                "estimated_cost_usd",
                "context_utilization");
    }

    @Test
    void prioritizesSloButRetainsOutOfSloFallbacks() {
        GatewayProperties properties = properties();
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        RoutingRequestProfile profile = new RoutingRequestProfile(
                512, 256, 0, false, 500L);

        ModelRouter.Plan plan = router.plan("smart", RoutingPolicy.QUALITY, profile);
        assertThat(plan.routes().get(0).provider().getId()).isEqualTo("economy");
        assertThat(plan.routes())
                .extracting(route -> route.provider().getId())
                .containsExactly("economy", "quality");
        assertThat(plan.routes().get(1).evidence().get("slo_met")).isZero();
    }

    @Test
    void onlyRequiresStreamingCapabilityForNativeProtocolPassthrough() {
        GatewayProperties properties = properties();
        GatewayProperties.Provider quality = properties.getProviders().get(0);
        quality.setSupportsStreaming(false);
        quality.setProtocol(Protocol.ANTHROPIC);
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        RoutingRequestProfile crossProtocol = new RoutingRequestProfile(
                512, 256, 0, true, null, Protocol.OPENAI);
        assertThat(router.plan("smart", RoutingPolicy.QUALITY, crossProtocol).routes())
                .extracting(route -> route.provider().getId())
                .contains("quality");

        quality.setProtocol(Protocol.OPENAI);
        assertThat(router.plan("smart", RoutingPolicy.QUALITY, crossProtocol).routes())
                .extracting(route -> route.provider().getId())
                .doesNotContain("quality");
    }

    @Test
    void removesAProviderFromPlansAfterTheCircuitOpens() {
        GatewayProperties properties = properties();
        properties.setFailureThreshold(2);
        properties.setCircuitOpenDuration(Duration.ofSeconds(30));
        ProviderHealthRegistry health = new ProviderHealthRegistry(properties);
        ModelRouter router = new ModelRouter(properties, health);

        health.failure("quality", 1_200);
        health.failure("quality", 1_300);

        assertThat(health.available("quality")).isFalse();
        assertThat(router.plan("smart", RoutingPolicy.QUALITY).routes())
                .extracting(route -> route.provider().getId())
                .containsExactly("economy");
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();

        GatewayProperties.Provider quality = new GatewayProperties.Provider();
        quality.setId("quality");
        quality.setBaseUrl("http://quality");
        quality.setModels(List.of("quality-model"));
        quality.setQualityScore(0.98);
        quality.setInputCostPerMillion(12);
        quality.setOutputCostPerMillion(24);
        quality.setExpectedLatencyMs(1_500);

        GatewayProperties.Provider economy = new GatewayProperties.Provider();
        economy.setId("economy");
        economy.setBaseUrl("http://economy");
        economy.setModels(List.of("economy-model"));
        economy.setQualityScore(0.72);
        economy.setInputCostPerMillion(0.1);
        economy.setOutputCostPerMillion(0.2);
        economy.setExpectedLatencyMs(150);

        GatewayProperties.ModelAlias qualityAlias = alias("smart", "quality", "quality-model");
        GatewayProperties.ModelAlias economyAlias = alias("smart", "economy", "economy-model");
        properties.setProviders(List.of(quality, economy));
        properties.setAliases(List.of(qualityAlias, economyAlias));
        return properties;
    }

    private GatewayProperties.ModelAlias alias(String name, String provider, String model) {
        GatewayProperties.ModelAlias alias = new GatewayProperties.ModelAlias();
        alias.setName(name);
        alias.setProvider(provider);
        alias.setModel(model);
        return alias;
    }
}
