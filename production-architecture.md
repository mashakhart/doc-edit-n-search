# Contract Redlining Tool — Production Architecture

*Turning the in-memory prototype into a production service. Each area notes the
trade-off and a priority: **P0** ship-first → **P2** later. Grounded in what the
prototype already does — the `version`/`If-Match` optimistic lock, the single
mutation choke point, the async re-index.*

![Architecture: Client → API Gateway, which routes document traffic to the Document service (API pods, Postgres with read replicas, Redis) and search traffic to the Search service (Indexer, OpenSearch). The Document service publishes change events to a Queue that the Search service consumes.](architecture.png)

## Architecture & Infra

| Piece | What it does (plain English) | How we'd build it |
|---|---|---|
| **Front door** ("edge") | the single entrance all traffic passes through — checks who you are, blocks abuse, routes you onward | CDN + **API gateway** (handles HTTPS, login checks, rate limits, routing) |
| **App servers** | run the actual application | several identical copies in containers that scale up and down with traffic (Kubernetes/ECS); they hold no data themselves, so we can add more freely |
| **Document store** | the permanent home for every document and its version history | a **Postgres** database — one row per document; the version number is what stops a newer edit from being silently overwritten |
| **Big-file storage** | a cheap place for large attachments (PDFs, exports) | cloud file storage (S3), with the database keeping just a link — added only if we actually store big files |
| **Search** | find documents by their text | a dedicated search engine (**OpenSearch**) from day one — sized for real volume, kept in sync by the queue |
| **Keeping search fresh** | update search right after each edit, without slowing the edit down | a background queue + workers do it just after the save |
| **Speed-up cache** | serve popular documents fast without hitting the database every time | **Redis** (a fast in-memory store); because every edit changes the version, out-of-date data is never served |

> **Why a regular (SQL) database, not "NoSQL"?** Every edit safely checks-and-updates a version number so two people can't overwrite each other. SQL databases like Postgres do that reliably and can still hold each document's flexible structure as JSON. NoSQL stores shine at huge scale but make that safe check harder — only worth it much later.

> **One app, or many "services"?** The code already separates editing from search. The clean first step is to split **search** into its own service (it grows and scales differently) and keep the rest together. Splitting into many services too early just adds moving parts to run and debug, so we do it only when the need is real.

## CI/CD & Deployment
- **Feature branch per change → PR.** Each change lands on its own branch and merges via a reviewed PR (required approval + green CI) — the review gate is where teammates give input and catch issues before `main`. Keep branches short-lived so they don't drift. **(P0)**
- **CI on every PR:** build → unit tests → **integration tests** (boot the app against a throwaway Postgres, hit the real endpoints) → lint → image scan → push image. **(P0)**
- **Promotion pipeline** — automated up to the prod-facing gate:

  `PR merged → test (auto) → int (auto) → ⟨manual approval⟩ → staging → prod`

  - **test:** auto-deploy on merge; **canary + integration/smoke tests** must pass to promote.
  - **int:** auto-promote once test is green (broader end-to-end checks).
  - **staging + prod:** deploy the **latest image that passed int** (promote the exact artifact, no rebuild), behind **manual approval**, then a **canary rollout** — small traffic slice first, watch error rate / p99, auto-rollback on regression, then ramp to 100%.
- Schema changes ship as versioned, backward-compatible migrations (Flyway/Liquibase) — needed because a canary runs the **old and new versions at once** against one database.

## Security & Compliance
- **Authentication — "who are you?"** OAuth2 / OIDC SSO (Okta/Auth0/Cognito): users log in with their company account so we never store passwords; short-lived JWT + refresh. **(P0)**
- **Authorization — "what may you do?"** Per-document RBAC — roles decide who can read vs. edit which contracts. **(P0)**
- **Encryption.** *In transit* (TLS 1.2+): scramble data on the wire (the browser padlock). *At rest* (AES-256 via managed KMS): stored data and backups are gibberish without the key. Secrets live in a vault, never in env files.
- **Audit log.** Append-only "who did what, when" for every read/write — legal traceability for contract edits, and a SOC 2 requirement. **(P0)**
- **GDPR** (EU privacy law): per-user data **export** and **hard-delete**, a retention policy, and region pinning so data stays where it's allowed.
- **SOC 2** (an outside auditor certifying your security hygiene): access reviews, change management, centralized logging — table stakes before enterprises trust you with their contracts.

## Scalability & Resilience
- Stateless API → HPA autoscale on CPU/RPS; Postgres primary + read replicas, multi-AZ with automated failover. **(P0)**
- Queue-based indexing absorbs write bursts (backpressure) and isolates a slow index from user-facing writes.
- Correctness already in place: optimistic locking (`version`/`If-Match`) prevents lost updates; add a client request-id to make writes **idempotent** on retry.
- Multi-region active-passive for DR — added only when the SLA justifies the cost. **(P2)**

## Monitoring & Observability
- **Metrics (per endpoint):** requests/sec, latency p50/p95/p99, error rate + success rate, CPU utilization and CPU-per-request (efficiency / right-sizing signal), plus queue lag and DB pool + replica lag — Prometheus/Grafana. **(P0)**
- **Logs:** structured JSON, request-id correlated → Loki/ELK. **Tracing:** OpenTelemetry across API → DB → indexer. **(P1)**
- **Alerts:** p99 latency past target, error rate above threshold, queue/replica lag, unhealthy pods — paged on **error-budget burn rate** (how fast you're using up the month's allowed error/downtime budget), so a one-off blip stays quiet but a sustained regression pages fast.

## Operations & Cost
- Right-size from real metrics; indexer workers scale to zero when the queue is empty.
- Single region first; multi-region only when DR/SLA demands it (real cost + complexity). **(P2)**
- Guardrails: budget alerts, S3 lifecycle tiering, prefer managed services early (buy-vs-build) and revisit at scale.

---

**Priority summary** — **P0:** Postgres persistence · **OpenSearch** search · SSO + audit log · CI/CD with safe rollout · core metrics & alerts.  **P1:** Redis cache · distributed tracing.  **P2:** multi-region DR.
