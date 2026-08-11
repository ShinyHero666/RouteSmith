# 简历项目表述

## RouteSmith 智路网关｜多模型智能路由与治理平台

- 主导实现 Java 17 / Spring WebFlux 多模型网关，以 Provider Adapter 统一 OpenAI Chat 与 Anthropic Messages 的消息、Tool Call、停止原因、usage 和 SSE 语义，为 SDK、Claude Code 与 Agent 应用提供单一访问端点。
- 将静态模型别名升级为请求感知路由：先按上下文窗口、Tool 与原生流式能力过滤候选，再结合输入/输出规模、Provider 单价、Prefill/Decode 吞吐、EWMA 延迟和历史可靠性估算请求级成本与延迟；支持质量/成本/延迟/均衡策略及可选延迟 SLO。
- 实现 Provider 连续失败熔断与 429/5xx/超时/断连故障转移，以有限有序路由计划保证每个候选最多尝试一次；将 token 额度提升为请求级预留/结算，结合 API Key 限流、幂等缓存和 SQL 用量账本，避免 fallback 重复占用预算或重复计费。
- 构建可审计路由 Trace，记录请求画像、候选资格、分数分解、预测延迟、SLO 命中、实际尝试链与降级终态，并通过响应头和管理端暴露脱敏证据；16 个自动化测试全部通过，另以 8 次真实 DeepSeek 请求验证链路，成功率 100%。

**技术栈：** Java 17、Spring WebFlux、Reactor、WebClient、Flyway、H2/PostgreSQL、SSE、JUnit 5、MockWebServer、OpenAI/Anthropic API