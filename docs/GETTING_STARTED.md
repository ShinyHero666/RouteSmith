# Getting Started

This is the shortest supported path from a clean clone to a working RouteSmith
request. It uses Docker Compose, PostgreSQL, the dashboard, and the repository's
DeepSeek example. Use [Providers](PROVIDERS.md) when you want a local runtime or
another hosted Provider.

## 1. Prerequisites

- Git.
- Docker Engine or Docker Desktop with Compose v2.
- A Provider account and API key.
- Free local ports `33002` and `38082`.

RouteSmith stores all runtime state in PostgreSQL. The Compose stack supplies it;
you do not need to install PostgreSQL on the host.

## 2. Create Local Configuration

```bash
git clone https://github.com/ShinyHero666/RouteSmith.git
cd RouteSmith
cp deploy/docker/modelport.env.example .env
cp config.example.toml config.toml
```

Both copies are required. Compose mounts `.env` and `config.toml` read-only into
the backend container.

Edit `.env` and replace every required `replace-with-...` value:

```env
MODELPORT_AUTH_TOKEN=<long-random-router-token>
MODELPORT_ADMIN_USERNAME=admin
MODELPORT_ADMIN_PASSWORD=<different-strong-admin-password>
MODELPORT_POSTGRES_PASSWORD=<long-url-safe-database-password>

DEEPSEEK_ANTHROPIC_AUTH_TOKEN=<real-provider-key>
ANTHROPIC_AUTH_TOKEN=<same-value-as-MODELPORT_AUTH_TOKEN>
```

Do not commit `.env` or `config.toml`. Provider credentials remain in RouteSmith;
client applications receive a RouteSmith token or a scoped client API key.

The sample model is `deepseek-v4-flash`. If the Provider account exposes a
different ID, update `DEEPSEEK_MODEL`, the `config.toml` model list/default, and
the request examples together.

## 3. Check The Setup

Run the read-only Linux and Compose preflight before a potentially long image
build:

```bash
scripts/doctor.sh --setup
```

Fix the first `[fail]` and rerun it. This mode does not start services or make a
Provider request.

## 4. Build And Start

```bash
docker compose config --quiet
scripts/build-container.sh
scripts/compose-up.sh
docker compose ps
```

The first image build compiles Rust and installs dashboard dependencies, so it
takes longer than subsequent builds. The build helper refuses a dirty worktree
for release provenance; use `--allow-dirty` only for local development.

Expected services:

| Service | Expected state |
| --- | --- |
| `postgres` | healthy |
| `modelport` | healthy |
| `dashboard` | running |

If a service does not start:

```bash
docker compose logs --tail=100 postgres
docker compose logs --tail=100 modelport
docker compose logs --tail=100 dashboard
```

## 5. Verify The Gateway

```bash
scripts/smoke-test.sh
```

This checks process liveness, authenticated storage readiness, and the model
catalog without generating model output.

Open `http://127.0.0.1:33002` and sign in with the configured administrator
username and password. The backend API remains at
`http://127.0.0.1:38082`.

## 6. Send The First Request

This request can consume Provider quota:

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

Or run the synthetic upstream smoke path:

```bash
scripts/smoke-test.sh --upstream
```

Open **Request Logs** in the dashboard and confirm the selected Provider/model,
status, latency, token provenance, and estimated cost. A Provider-returned usage
value is still not an authoritative invoice.

## 7. Connect A Client

Claude Code or another Anthropic-compatible client:

```env
ANTHROPIC_BASE_URL=http://127.0.0.1:38082
ANTHROPIC_AUTH_TOKEN=<MODELPORT_AUTH_TOKEN>
ANTHROPIC_MODEL=deepseek-v4-flash
```

OpenAI-compatible SDK:

```env
OPENAI_BASE_URL=http://127.0.0.1:38082/v1
OPENAI_API_KEY=<MODELPORT_AUTH_TOKEN>
OPENAI_MODEL=deepseek-v4-flash
```

For shared use, create a real user and a scoped client API key in the dashboard,
then set `MODELPORT_REQUIRE_CONTROL_API_KEYS=1` during production hardening.
Never give a client the upstream Provider key.

## 8. Stop, Restart, Or Upgrade

```bash
docker compose stop
docker compose start
docker compose logs -f modelport
docker compose down
```

`docker compose down` preserves named volumes. Do not use
`docker compose down -v` unless permanent database deletion is intentional and
a verified backup exists.

Before an upgrade, follow [Operations: Backup And Restore](OPERATIONS.md#backup-and-restore)
and [Production](PRODUCTION.md).

## Common First-Run Problems

| Symptom | Resolution |
| --- | --- |
| Compose reports missing `config.toml` | Copy `config.example.toml` to `config.toml`. |
| Startup rejects a placeholder | Replace every required `replace-with-...` value in `.env`. |
| Dashboard opens but login fails | Use the admin username/password, not the router token. |
| `/v1/*` returns 401 | Send `x-api-key: <MODELPORT_AUTH_TOKEN>` or `Authorization: Bearer <key>`. |
| Model is not listed | Align the Provider's configured model ID with the account/runtime catalog. |
| Local runtime is unreachable from Docker | Use `host.docker.internal`, not container loopback. |
| Stream starts with HTTP 200 then fails | Inspect the SSE `event: error` and matching request log. |
| Port is already allocated | Change `MODELPORT_API_PUBLISH` or `MODELPORT_DASHBOARD_PUBLISH` in `.env`. |

Continue with [Configuration](CONFIGURATION.md), [Providers](PROVIDERS.md),
[Deployment](DEPLOYMENT.md), or [Operations](OPERATIONS.md) only when your task
requires the additional detail.
