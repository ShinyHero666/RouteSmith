# Releasing RouteSmith

RouteSmith uses semantic versions shared by the Rust backend and dashboard. A
release tag is `v<version>`, matching `Cargo.toml` and
`dashboard/package.json`.

## Release Preconditions

1. The worktree is clean and the release commit is on protected `main`.
2. `CHANGELOG.md` describes user-visible behavior, security changes, breaking
   storage/configuration changes, and remaining limits.
3. `scripts/check-all.sh`, PostgreSQL-backed dashboard E2E, dependency audit,
   CodeQL, dependency review, and Scorecard checks are green.
   Any temporary dashboard audit exception must still match its documented
   deployment exposure and remain unexpired.
4. A clean PostgreSQL migration and the old-row rejection fixture have been
   verified.
5. Documentation, configuration examples, Compose, systemd, and dashboard use
   the same versioned contract.
6. Any real-Provider claim has a dated, secret-free, commit-bound evidence
   artifact. Routine release CI makes no paid Provider calls.

## Version And Tag

Update:

- `Cargo.toml`;
- `dashboard/package.json` and its lockfile;
- the changelog comparison links and release section.

Then run:

```bash
scripts/check-all.sh
git diff --check
git tag -s vX.Y.Z -m "RouteSmith vX.Y.Z"
git push origin main vX.Y.Z
```

A signed tag is preferred. If the maintainer cannot sign tags, document that
fact in the release notes rather than implying signature verification.

## Automated Outputs

The [release workflow](../.github/workflows/release.yml), when run successfully:

- rejects a tag that does not match the backend, dashboard, and dashboard
  lockfile versions;
- invokes the complete reusable CI workflow, including the explicit
  PostgreSQL legacy-row migration test, state revision concurrency/atomicity
  tests, transactional ledger test, and PostgreSQL-backed dashboard E2E;
- prevents binary or container publication until that verification succeeds;
- builds the Linux amd64 backend archive;
- emits SHA-256 checksums and an SPDX JSON SBOM;
- creates GitHub build-provenance and SBOM attestations;
- publishes versioned backend and dashboard images to GHCR;
- attests the published container digests;
- creates the GitHub Release from the existing tag.

Release workflows use least-privilege job permissions and pin third-party
Actions to complete commit SHAs.

## Verification

For a published RouteSmith release, download its archive and `SHA256SUMS`
from [this repository's releases](https://github.com/ShinyHero666/RouteSmith/releases),
replace `X.Y.Z` with that release version, and verify checksums and GitHub
attestations. If no release assets are available, use the source build in
[Getting Started](GETTING_STARTED.md); these examples do not establish that a
release or container image has already been published.

```bash
sha256sum --check SHA256SUMS
gh attestation verify model-port-vX.Y.Z-linux-amd64.tar.gz \
  --repo ShinyHero666/RouteSmith
```

For a container published by a successful RouteSmith release workflow, obtain
the exact image reference and immutable digest from that run's outputs. Verify
that digest rather than relying only on a mutable tag. Do not substitute an
upstream ModelPort image for a RouteSmith release.

## Rollback

Application rollback and database rollback are separate decisions.

- Keep the previous image digest, binary, configuration, and database-native
  backup until production acceptance passes.
- Never point an older release at a database after a migration unless that
  downgrade path was explicitly tested.
- The current clean operational baseline does not import old request/attempt or
  JSON state. Roll back by restoring the previous database and application
  together.
- The PostgreSQL 18 Compose baseline uses a new
  `modelport_modelport-postgres-18` volume and the versioned
  `/var/lib/postgresql/18/docker` data directory. It intentionally does not
  reuse the old PostgreSQL 16 volume. Back up the old deployment before
  upgrading, follow [the migration runbook](POSTGRESQL_MIGRATION.md), and do not
  delete its volume until restore acceptance passes.

## Repository Settings

Maintainers must enable private vulnerability reporting, Dependabot alerts and
security updates, immutable releases where available, tag protection/rulesets,
and required status checks. Repository settings are external state and cannot
be guaranteed by workflow files alone.
