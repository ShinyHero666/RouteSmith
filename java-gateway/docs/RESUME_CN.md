# 简历项目表述

## RouteSmith 智路网关｜多模型智能路由与治理平台

- 主导实现 Java 17 / Spring WebFlux 多模型网关，以 Provider Adapter 统一 OpenAI Chat 与 Anthropic Messages 的消息、Tool Call、停止原因、Usage 和 SSE 语义；协议契约测试覆盖双向请求/响应及工具调用转换，为 SDK、Claude Code 与 Agent 应用提供单一访问端点。
- 将静态模型别名升级为请求感知路由：先按上下文窗口、Tool 与原生流式等硬能力过滤候选，再依据输入/输出 Token 规模、Provider 单价、Prefill/Decode 吞吐、EWMA 延迟及可靠性估算单请求成本与时延；6 组路由回归覆盖策略差异、能力过滤、长 Prompt、延迟 SLO 与跨协议流式边界。
- 实现 Provider 连续失败熔断及 429/5xx/超时/断连故障转移，以有限有序计划确保每个候选最多尝试一次；设计 Token 预算“预留-结算-释放”账本，50 个并发 100-Token 预留竞争 1,000-Token 额度时仅放行 10 个；首选 503 + 备用断连注入中两个候选各调用 1 次并返回明确 502 终态。
- 构建可审计 Routing Trace，记录请求画像、候选资格、评分分解、预测延迟、SLO 命中、实际尝试链与降级终态，并通过响应头及管理端暴露脱敏证据；8 次真实 DeepSeek 端到端烟测全部成功且 0 降级，样本内 Provider 延迟 P50/P95 为 1222/1410 ms。

**技术栈：** Java 17、Spring WebFlux、Reactor、WebClient、Flyway、H2/PostgreSQL、SSE、JUnit 5、MockWebServer、OpenAI/Anthropic API
