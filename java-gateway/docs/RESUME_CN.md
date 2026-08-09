# 简历项目表述

## RouteSmith 智路网关｜多模型智能路由与治理平台

- 主导实现 Java 17 / Spring WebFlux 多模型网关，统一 OpenAI 与 Anthropic 的 Chat、Messages、Tool Call 和 SSE 协议，为 Claude Code、SDK 与 Agent 应用提供单一模型访问端点及跨协议适配能力。
- 将静态模型别名升级为可解释多因子路由，综合模型质量、调用成本、EWMA 延迟与历史可靠性打分，支持质量/成本/延迟/均衡四类策略，并通过响应头和管理端 Trace 暴露候选分数、实际路由与降级证据。
- 实现 Provider 健康状态、连续失败熔断及 429/5xx/超时/断连故障转移，保证候选节点最多尝试一次；结合 API Key 限流、请求级 token 预算预留、幂等缓存和 SQL 用量账本，避免 fallback 重复占用额度。
- 构建 12 个自动化测试覆盖协议转换、配额、SSE、路由、熔断及级联断连；真实接入 DeepSeek 完成 8 次请求，成功率 100%、0 次降级，Provider 侧 P50/P95 延迟为 1222/1410 ms，并保留完整脱敏路由证据。

**技术栈：** Java 17、Spring WebFlux、Reactor、WebClient、Flyway、H2/PostgreSQL、SSE、JUnit 5、MockWebServer、OpenAI/Anthropic API
