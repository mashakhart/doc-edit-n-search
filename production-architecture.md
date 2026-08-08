# Contract Redlining Tool — Production Architecture

*Turning the in-memory prototype into a production service. Each area notes the
trade-off and a priority: **P0** ship-first → **P2** later. Grounded in what the
prototype already does — the `version`/`If-Match` optimistic lock, the single
mutation choke point, the async re-index.*

![Architecture: Client → API Gateway, which routes document traffic to the Document service (API pods, Postgres with read replicas, Redis) and search traffic to the Search service (Indexer, OpenSearch). The Document service publishes change events to a Queue that the Search service consumes.](architecture.png)

## Architecture & Infra

| Concern | Prototype (today) | Production | Why / trade-off |
|---|---|---|---|
| **Front door** | direct HTTP to one Spring app | CDN + **API gateway** (TLS, auth, rate-limit, routing) | one guarded entrance for the cross-cutting concerns |
| **App servers** | a single Spring process | stateless containers, autoscaled (K8s/ECS) | app holds no state → add copies freely under load |
| **Ingestion** | users paste or type plain text | upload **PDF/DOCX → text-extraction + OCR** pipeline → document text | contracts arrive as PDFs; scanned ones need OCR before we can redline or search them |
| **Document store** | in-memory `ConcurrentHashMap` | **Postgres** — a row per doc, `version` = the optimistic lock | durability; the `If-Match` check maps 1:1 to DB row versioning |
| **Original files** | none (text only, in memory) | object store (S3) holds the uploaded file; DB keeps a link | keep 10 MB+ binaries out of the DB; the extracted text lives in Postgres + search |
| **Search** | in-memory inverted index (prefix) | dedicated engine (**OpenSearch**) from day one | sized for real volume; avoids a re-indexing migration later |
| **Keeping search fresh** | single-thread executor | durable queue (SQS/Kafka) + workers | survives restarts; decouples the write from indexing |
| **Cache** | none | **Redis** — cache a doc by `id + version` | cut read latency; a new version = a new key, so no stale reads |

> **Ingestion pipeline (PDF → text).** An upload lands in object storage, then a background worker extracts its text: digital PDFs carry a text layer that's read directly (e.g. Apache PDFBox), while **scanned / image-only PDFs need OCR** (Tesseract, or a managed service like AWS Textract) to turn pixels into characters. The extracted text becomes a normal document — redlinable and indexed for search — while the original file is kept for reference. It runs off the request thread, on the same queue as re-indexing.

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

- **Autoscaling & failover.** The API servers hold no state, so we add or drop copies automatically with load; Postgres runs as a primary with read replicas across availability zones, promoting a replica automatically if the primary fails. **(P0)**
- **Backpressure via the queue.** Indexing behind a queue absorbs bursts of writes and keeps a slow index from holding up live edits.
- **Conflict-safe, idempotent writes.** The version / If-Match check already prevents silent overwrites; a client request id would make a retried write safe to apply exactly once.
- **Disaster recovery.** A standby second region only earns its cost once our uptime promises demand it. **(P2)**

## Monitoring & Observability

- **Metrics.** Per endpoint: requests/sec, latency (p50/p95/p99), error and success rates, and CPU both in total and per request (a right-sizing signal) — plus the queue's backlog and the database's pool and replica lag, in Prometheus/Grafana. **(P0)**
- **Logs & tracing.** Structured JSON logs tagged with a request id let us follow one request across the system (Loki or ELK); OpenTelemetry traces show its full path from API to database to indexer. **(P1)**
- **Alerts.** We page on high p99 latency, a persistent error rate, queue or replica lag, or unhealthy servers — firing only on a *sustained* problem, never a one-second blip.

## Operations & Cost

- **Right-sizing.** We size machines from real metrics and let indexing workers scale to zero when the queue is empty, so we don't pay for idle capacity.
- **Single region first.** We add a second region only when disaster recovery or uptime promises truly require it. **(P2)**
- **Cost guardrails.** Budget alerts, automatic tiering of older files to cheaper storage, and leaning on managed services early (buy over build) until scale makes self-hosting worth it.

---

**Priority summary** — **P0:** Postgres persistence · **OpenSearch** search · SSO + audit log · CI/CD with safe rollout · core metrics & alerts.  **P1:** Redis cache · distributed tracing.  **P2:** multi-region DR.
