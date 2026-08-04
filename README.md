# ModelHub · Multi-model Gateway and Governance

[![CI](https://github.com/ShinyHero666/ModelHub-Gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/ShinyHero666/ModelHub-Gateway/actions/workflows/ci.yml)
[![CodeQL](https://github.com/ShinyHero666/ModelHub-Gateway/actions/workflows/codeql.yml/badge.svg)](https://github.com/ShinyHero666/ModelHub-Gateway/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**English** | [简体中文](README.zh-CN.md)

ModelHub is a self-hosted LLM gateway for Anthropic-compatible and
OpenAI-compatible clients. It gives Claude Code, SDKs, and internal
applications one endpoint for authentication, model routing, quotas, usage,
Provider health, request evidence, and operations.

![ModelPort architecture overview](docs/assets/modelport-overview.svg)

## What You Get

- `POST /v1/messages`, `POST /v1/chat/completions`, `GET /v1/models`, and
  opt-in exact token counting.
- Anthropic and OpenAI-compatible Provider adapters with bounded streaming and
  Tool Use conversion.
- Optional CPA Codex and Claude account channels that remain internal Providers
  behind ModelPort's policy, routing, and evidence boundary.
- Deterministic routes plus opt-in explainable smart routing with shadow mode,
  stable canaries, and durable decision evidence.
- Scoped client API keys, users, teams, quotas, spend controls, Provider
  credential pools, cooldown, and bounded fallback.
- A React operations dashboard and a PostgreSQL request, usage, budget, and
  audit ledger.
- Docker Compose and systemd deployment paths, backup/restore tooling,
  Prometheus metrics, and acceptance scripts.

ModelPort currently supports one trusted host or a small trusted network. It is
not a public multi-tenant service, model runtime, chat UI, payment processor, or
Provider invoice. See [Production](docs/PRODUCTION.md) and
[Roadmap](docs/ROADMAP.md) before making broader availability claims.

## Quick Start

Requirements: Git, Docker, Docker Compose v2, and credentials for at least one
Provider. The maintained example uses DeepSeek's Anthropic-compatible endpoint.

```bash
git clone https://github.com/tiammomo/ModelPort.git
cd ModelPort
cp deploy/docker/modelport.env.example .env
cp config.example.toml config.toml
```

Edit `.env` and replace every required `replace-with-...` value. At minimum set
unique router, administrator, PostgreSQL, and Provider credentials. Keep
`MODELPORT_AUTH_TOKEN` and the client-side `ANTHROPIC_AUTH_TOKEN` equal for the
first local test.

```bash
scripts/doctor.sh --setup
scripts/build-container.sh
scripts/compose-up.sh
docker compose ps
scripts/smoke-test.sh
```

Open `http://127.0.0.1:33002` and sign in with
`MODELPORT_ADMIN_USERNAME`/`MODELPORT_ADMIN_PASSWORD`.

For local Qwen, another Provider, production hardening, or troubleshooting,
follow the tested [Getting Started guide](docs/GETTING_STARTED.md).

## Send Your First Request

```bash
source .env

curl -fsS \
  -H "x-api-key: $MODELPORT_AUTH_TOKEN" \
  -H 'content-type: application/json' \
  http://127.0.0.1:38082/v1/messages \
  -d '{
    "model":"deepseek-v4-flash",
    "max_tokens":96,
    "messages":[{"role":"user","content":"Reply exactly: OK"}]
  }'
```

This call can consume Provider quota. `scripts/smoke-test.sh` is local-only;
use `scripts/smoke-test.sh --upstream` when a paid synthetic call is intended.

Claude Code:

```env
ANTHROPIC_BASE_URL=http://127.0.0.1:38082
ANTHROPIC_AUTH_TOKEN=<MODELPORT_AUTH_TOKEN>
ANTHROPIC_MODEL=deepseek-v4-flash
```

OpenAI-compatible SDK:

```env
OPENAI_BASE_URL=http://127.0.0.1:38082/v1
OPENAI_API_KEY=<MODELPORT_CLIENT_KEY>
OPENAI_MODEL=deepseek-v4-flash
```

Use a dashboard-issued scoped client key for shared deployments. Provider keys
stay in ModelPort and must never be copied into client applications.

## Documentation

Choose the document for your task instead of reading the whole documentation
set:

- [Getting Started](docs/GETTING_STARTED.md) — install, first login, first
  request, and common startup failures.
- [Learning Path](docs/LEARNING_PATH.md) — role-based 30–60 minute operator,
  client-integration, operations, and contributor tracks.
- [Local inference joint quickstart](docs/LOCAL_INFERENCE_STACK.md) — a
  Linux/WSL2 contract-first path for ModelPort plus local-inference-stack.
- [Configuration](docs/CONFIGURATION.md) — environment and TOML reference.
- [API](docs/API.md) — client and control-plane contracts.
- [Providers](docs/PROVIDERS.md) — hosted Providers, local runtimes, and
  compatibility evidence.
- [Smart Routing](docs/SMART_ROUTING.md) — scoring, shadow, canary, and
  rollback.
- [Deployment](docs/DEPLOYMENT.md) — Docker Compose, systemd, and production
  topology.
- [Operations](docs/OPERATIONS.md) — health, logs, metrics, backup, retention,
  incidents, and upgrades.
- [Production](docs/PRODUCTION.md) — go-live and release acceptance.
- [Development](docs/DEVELOPMENT.md) — contributor workflow and test matrix.
- [Documentation index](docs/README.md) — role-based navigation.

## Security And Support

Keep backend and PostgreSQL ports private. Use same-origin HTTPS, exact trusted
proxy CIDRs, secure cookies, CSRF protection, and dashboard-issued API keys for
shared use. Never commit `.env`, Provider keys, backups, prompts, responses, or
raw sensitive logs.

Read [Security](SECURITY.md), [Privacy](PRIVACY.md), [Support](SUPPORT.md), and
[Governance](GOVERNANCE.md). Community support has no SLA unless a separate
written agreement provides one.

## Development

```bash
cp .env.example .env
cp config.example.toml config.toml
# replace required placeholders
scripts/start.sh

cd dashboard
npm ci
npm run dev
```

Before submitting a change:

```bash
scripts/check-all.sh
```

## License

[MIT](LICENSE)
