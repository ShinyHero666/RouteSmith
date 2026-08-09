# RouteSmith Java gateway

RouteSmith is a Java 17 / Spring WebFlux gateway for OpenAI-compatible and
Anthropic-compatible clients. It is infrastructure, not an Agent: routing,
protocol conversion, quotas and failure handling remain deterministic.

## Implemented capabilities

- OpenAI-compatible `/v1/chat/completions` and `/v1/models`;
- Anthropic-compatible `/v1/messages` and `/v1/messages/count_tokens`;
- request, response and Tool Call conversion between both protocols;
- same-protocol SSE passthrough and finite cross-protocol stream adaptation;
- model aliases with multiple eligible Provider routes;
- explainable routing by quality, cost, EWMA latency and reliability;
- `BALANCED`, `QUALITY`, `ECONOMY` and `LATENCY` policies;
- Provider health tracking, consecutive-failure circuits and bounded fallback;
- one attempt per candidate on 429, 5xx, timeout or transport failure;
- API-key authentication, request limits and monthly token budgets;
- request-level reservation, success settlement and failure release;
- durable idempotent non-stream responses and an append-only SQL usage ledger;
- redacted Provider health, request evidence and routing-decision admin views;
- file H2 locally and PostgreSQL by configuration.

## Run locally

```powershell
$env:MODELPORT_CLIENT_KEY="replace-me"
$env:MODELPORT_UPSTREAM_URL="http://127.0.0.1:8000"
mvn test
mvn spring-boot:run
```

Enable DeepSeek without writing the Provider key to the repository:

```powershell
$env:DEEPSEEK_API_KEY="replace-me"
$env:DEEPSEEK_ENABLED="true"
$env:MODELPORT_LOCAL_ENABLED="false"
mvn spring-boot:run
```

Request an explicit policy with the `x-modelport-routing-policy` header. The
response includes `x-modelport-route`, `x-modelport-route-score` and
`x-modelport-fallback-count`.

## Persistence

The local database is `./data/modelport`. Select PostgreSQL with:

```powershell
$env:MODELPORT_DB_URL="jdbc:postgresql://127.0.0.1:5432/modelport"
$env:MODELPORT_DB_USERNAME="modelport"
$env:MODELPORT_DB_PASSWORD="replace-me"
```

See [`docs/PROJECT_REVIEW_CN.md`](docs/PROJECT_REVIEW_CN.md) for the routing
formula, real DeepSeek measurements, failure tests, current boundaries and
interview questions.

The Rust implementation remains a wider behavior reference. The Java module
does not claim complete OIDC, distributed health state, Provider credential
pools or the full dashboard administration surface.
