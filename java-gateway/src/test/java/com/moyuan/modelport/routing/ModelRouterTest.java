package com.moyuan.modelport.routing;

import com.moyuan.modelport.config.GatewayProperties;
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
