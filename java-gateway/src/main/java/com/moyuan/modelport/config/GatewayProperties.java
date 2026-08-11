package com.moyuan.modelport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("modelport")
public class GatewayProperties {
    private Duration requestTimeout = Duration.ofSeconds(90);
    private int maxRequestBytes = 2 * 1024 * 1024;
    private RoutingPolicy defaultRoutingPolicy = RoutingPolicy.BALANCED;
    private int failureThreshold = 2;
    private Duration circuitOpenDuration = Duration.ofSeconds(30);
    private List<ClientKey> clientKeys = new ArrayList<>();
    private List<Provider> providers = new ArrayList<>();
    private List<ModelAlias> aliases = new ArrayList<>();

    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaxRequestBytes() { return maxRequestBytes; }
    public void setMaxRequestBytes(int maxRequestBytes) { this.maxRequestBytes = maxRequestBytes; }
    public RoutingPolicy getDefaultRoutingPolicy() { return defaultRoutingPolicy; }
    public void setDefaultRoutingPolicy(RoutingPolicy defaultRoutingPolicy) {
        this.defaultRoutingPolicy = defaultRoutingPolicy;
    }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public Duration getCircuitOpenDuration() { return circuitOpenDuration; }
    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }
    public List<ClientKey> getClientKeys() { return clientKeys; }
    public void setClientKeys(List<ClientKey> clientKeys) { this.clientKeys = clientKeys; }
    public List<Provider> getProviders() { return providers; }
    public void setProviders(List<Provider> providers) { this.providers = providers; }
    public List<ModelAlias> getAliases() { return aliases; }
    public void setAliases(List<ModelAlias> aliases) { this.aliases = aliases; }

    public enum Protocol {
        OPENAI, ANTHROPIC
    }

    public enum RoutingPolicy {
        BALANCED, QUALITY, ECONOMY, LATENCY
    }

    public static class ClientKey {
        private String id;
        private String secret;
        private String tenant = "default";
        private int requestsPerMinute = 60;
        private long monthlyTokenBudget = 1_000_000;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getTenant() { return tenant; }
        public void setTenant(String tenant) { this.tenant = tenant; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }
        public long getMonthlyTokenBudget() { return monthlyTokenBudget; }
        public void setMonthlyTokenBudget(long monthlyTokenBudget) {
            this.monthlyTokenBudget = monthlyTokenBudget;
        }
    }

    public static class Provider {
        private String id;
        private String baseUrl;
        private Protocol protocol = Protocol.OPENAI;
        private String apiKey = "";
        private boolean enabled = true;
        private List<String> models = new ArrayList<>();
        private double qualityScore = 0.8;
        private double inputCostPerMillion = 1.0;
        private double outputCostPerMillion = 2.0;
        private long expectedLatencyMs = 1_000;
        private long maxContextTokens = 128_000;
        private boolean supportsTools = true;
        private boolean supportsStreaming = true;
        private double prefillTokensPerSecond = 2_500;
        private double decodeTokensPerSecond = 80;
        private long toolCallPenaltyMs = 250;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Protocol getProtocol() { return protocol; }
        public void setProtocol(Protocol protocol) { this.protocol = protocol; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = models; }
        public double getQualityScore() { return qualityScore; }
        public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }
        public double getInputCostPerMillion() { return inputCostPerMillion; }
        public void setInputCostPerMillion(double inputCostPerMillion) {
            this.inputCostPerMillion = inputCostPerMillion;
        }
        public double getOutputCostPerMillion() { return outputCostPerMillion; }
        public void setOutputCostPerMillion(double outputCostPerMillion) {
            this.outputCostPerMillion = outputCostPerMillion;
        }
        public long getExpectedLatencyMs() { return expectedLatencyMs; }
        public void setExpectedLatencyMs(long expectedLatencyMs) {
            this.expectedLatencyMs = expectedLatencyMs;
        }
        public long getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(long value) { this.maxContextTokens = value; }
        public boolean isSupportsTools() { return supportsTools; }
        public void setSupportsTools(boolean value) { this.supportsTools = value; }
        public boolean isSupportsStreaming() { return supportsStreaming; }
        public void setSupportsStreaming(boolean value) { this.supportsStreaming = value; }
        public double getPrefillTokensPerSecond() { return prefillTokensPerSecond; }
        public void setPrefillTokensPerSecond(double value) { this.prefillTokensPerSecond = value; }
        public double getDecodeTokensPerSecond() { return decodeTokensPerSecond; }
        public void setDecodeTokensPerSecond(double value) { this.decodeTokensPerSecond = value; }
        public long getToolCallPenaltyMs() { return toolCallPenaltyMs; }
        public void setToolCallPenaltyMs(long value) { this.toolCallPenaltyMs = value; }
    }

    public static class ModelAlias {
        private String name;
        private String provider;
        private String model;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
