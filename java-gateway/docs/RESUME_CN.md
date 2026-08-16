# 简历项目表述

## RouteSmith 智路网关｜多模型智能路由与治理平台

- **协议适配：**主导实现 Spring WebFlux 多模型网关，统一 OpenAI / Anthropic 消息、Tool Call 与 Usage；同协议 SSE 透传，跨协议转换后合成 SSE，为 SDK、Claude Code 和 Agent 提供统一端点。
- **请求路由：**先按上下文窗口、Tool 和流式能力硬过滤，再结合 Token 规模、单价、Prefill / Decode 吞吐与 EWMA 延迟估算成本和时延；支持质量、成本、延迟、均衡四类策略、延迟 SLO 及可审计 Trace。
- **故障治理：**实现连续失败熔断及 429 / 5xx / 超时 / 断连故障转移，保证每个候选最多尝试一次；故障注入验证主 Provider 连续 503 后自动旁路，主 503 与备用断连时各调用 1 次并返回明确 502 终态。
- **额度治理：**基于 Spring JDBC 事务行锁实现 Token 预算账本与请求幂等；50 个并发 100-Token 请求竞争 1,000-Token 额度时仅放行 10 个，100 次同幂等键顺序重放仅产生 1 次 Provider 调用。

**技术栈：** Java 17、Spring WebFlux、Reactor、WebClient、Spring JDBC、Flyway、H2 / PostgreSQL、SSE、JUnit 5、MockWebServer、OpenAI / Anthropic API

## 数字口径

- Maven 全量测试为 16 条，全部执行通过，0 失败、0 错误。
- 50 路预算竞争使用本地 H2 测试事务正确性，不外推为 PostgreSQL 生产吞吐。
- 100 次幂等重放为顺序请求，不描述为 100 并发。
- 跨协议流式为完整响应缓冲后的 SSE 合成，不描述为逐 Token 实时转换。
- 删除 8 次 DeepSeek 及 P50 / P95 表述：仓库未保存原始报告，不进入最终简历。
