# finance-service API

Direct local service URL: `http://localhost:8086`

Management URL (loopback by default): `http://127.0.0.1:9086`

`finance-service` is the independently deployable PostgreSQL system of record for FR31–FR36:
budgets, immutable financial postings, category corrections, spending insights, forecasts, and
financial-goal progress. Gateway routing is intentionally not claimed here until the gateway team
adds the public route. The service does not connect to banks, payment processors, FX providers, or
Kafka in this first version.

## Security and operating boundary

Every public endpoint requires `Authorization: Bearer <LifeOS access token>`. The service calls
Identity's workload-authenticated validation endpoint, then its V2 authorization decision endpoint
with workload identity `finance-service` and expected policy version `v2`. It uses only these exact
registered actions:

| Resource family | Actions |
| --- | --- |
| `finance-budget` | `finance:budget-create`, `finance:budget-list`, `finance:budget-read`, `finance:budget-update` |
| `finance-transaction` | `finance:transaction-create`, `finance:transaction-list`, `finance:transaction-read`, `finance:transaction-categorize` |
| `finance-goal` | `finance:goal-create`, `finance:goal-list`, `finance:goal-read`, `finance:goal-update`, `finance:goal-contribute` |
| `finance` collection | `finance:insights-read`, `finance:forecast-read` |

All are self-only personal-tenant V2 descriptors. The service additionally scopes every local read
and write by authenticated owner and tenant. Missing and cross-account object IDs produce the same
generic `404` response. Identity, audit, and bounded outbound-call failures fail closed with `503`.
Audit rows record only a correlation ID, bounded outcome/action tag, account reference where known,
and an HMACed client-address fingerprint; they never record monetary values, merchants,
categories, bearer tokens, raw client addresses, raw idempotency keys, or request bodies.

## Money, conditional writes, and idempotency

Money is represented as `{ "currency": "USD", "amountMinor": 12345 }`: an uppercase ISO 4217
currency and a positive signed-64-bit integer number of minor units. There are no decimals,
`double` values, FX conversions, implicit currency normalization, or external account integrations.
A posting's `direction` (`INCOME` or `EXPENSE`) supplies the sign in derived views; stored
`amountMinor` remains positive and immutable.

Every mutation requires an opaque `Idempotency-Key` matching `[A-Za-z0-9._-]{8,128}`. Creates also
require exactly `If-None-Match: *`; updates, categorization, and contributions require exactly one
strong numeric `If-Match` ETag such as `"4"`. A durable reservation is scoped to authenticated
actor, personal tenant, operation, and an HMACed key. It commits the mutation and original public
JSON response snapshot atomically. A matching authorized retry returns the original status,
`Location`, body, and ETag with `Idempotent-Replayed: true`; it never rereads a newer state.

| Status | Meaning |
| --- | --- |
| `200` | Conditional update, category correction, contribution, or exact replay of one. |
| `201` | Create or exact create replay; response includes original `Location`. |
| `400` | Invalid body, currency, paging bound, idempotency key, or conditional header. |
| `401` | Missing or rejected bearer; `WWW-Authenticate: Bearer` is supplied. |
| `403` | An explicit Identity decision denied the action. |
| `404` | Object is absent or outside caller scope. |
| `409` | Same idempotency key used for a different request, duplicate source contribution, or an unchanged category correction. |
| `412` | Stale ETag or overlapping budget scope. |
| `428` | Required `If-Match` or `If-None-Match: *` is missing. |
| `503` | Identity, audit, or idempotency cannot safely complete. |

## Budgets (FR31)

| Endpoint | Behavior |
| --- | --- |
| `POST /api/v1/finance/budgets` | Creates `{ category, currency, allocationMinor, periodStart, periodEnd }`; category is immutable. Returns `201` and `/api/v1/finance/budgets/{id}`. |
| `GET /api/v1/finance/budgets?page=0&pageSize=50` | Owner-scoped deterministic page (maximum page size 100), newest period first. |
| `GET /api/v1/finance/budgets/{id}` | Returns a budget and ETag. |
| `PUT /api/v1/finance/budgets/{id}` | Conditionally changes currency, allocation, and period but not category. |

`periodEnd` must be on or after `periodStart`. PostgreSQL enforces a `btree_gist` exclusion
constraint over owner, tenant, category, and inclusive date range; two distinct retry keys cannot
commit overlapping category budgets. The service also uses a portable precheck for its H2 test
path. A collision is mapped to `412` and its pending idempotency reservation is removed.

## Immutable transactions and category history (FR32–FR33)

| Endpoint | Behavior |
| --- | --- |
| `POST /api/v1/finance/transactions` | Records one immutable posting: `{ currency, amountMinor, direction, occurredOn, merchant?, category }`; returns `201`. |
| `GET /api/v1/finance/transactions?page=0&pageSize=50` | Deterministic owner-scoped page, ordered `occurredOn DESC, id DESC`. |
| `GET /api/v1/finance/transactions/{id}` | Returns the original and current category plus ETag. |
| `PUT /api/v1/finance/transactions/{id}/category` | Conditional category correction only; amount, currency, direction, date, merchant, and initial category cannot be rewritten. |
| `GET /api/v1/finance/transactions/{id}/category-history?page=0&pageSize=50` | Returns a bounded append-only correction page ordered by correction time and ID. |

Each correction appends `previousCategory`, `correctedCategory`, actor reference, and timestamp in
the same transaction as updating `currentCategory`; this maintains explainable correction history
without modifying the original posting.

## Insights and deterministic forecast (FR34–FR35)

`GET /api/v1/finance/insights?from=YYYY-MM-DD&to=YYYY-MM-DD&currency=USD&categoryPage=0&categoryPageSize=50`
returns owner-scoped income, expense, net, and a deterministic page of per-category totals for
**1–366 inclusive calendar days**. Category pages are 1–100 items (50 default; pages 0–1000).
It reads at most 10,000 selected-currency postings in deterministic date/ID order. If that fixed
work window would truncate, `truncated=true` and `POSTING_WINDOW_TRUNCATED` is included; callers
must not treat the response as a complete financial statement. `NO_FX_CONVERSION` is always
explicit and `OTHER_CURRENCIES_EXCLUDED` appears when the account has other currency postings in
the interval.

`GET /api/v1/finance/forecast?currency=USD&horizonWeeks=4` is pure and does not persist a forecast.
It considers only the preceding 52 completed Monday–Sunday weeks, never the currently incomplete
week, and requires at least 8 observed completed weeks. It computes integer nearest-rank p25/p50/
p75 weekly income, expense, and net values, then multiplies those exact integer statistics by the
requested 1–52 week horizon. Its response identifies
`NEAREST_RANK_WEEKLY_QUARTILES_V1`, source dates, sample count, and limitations. It returns
`available=false` with null estimate fields for `INSUFFICIENT_HISTORY` or `SOURCE_WINDOW_TOO_LARGE`;
it does not substitute zeroes, decimals, artificial confidence, or FX conversions.

## Financial goals and contributions (FR36)

| Endpoint | Behavior |
| --- | --- |
| `POST /api/v1/finance/goals` | Creates `{ name, currency, targetMinor, targetDate? }`; currency is immutable. |
| `GET /api/v1/finance/goals?page=0&pageSize=50` | Deterministic owner-scoped page with integer contribution totals. |
| `GET /api/v1/finance/goals/{id}` | Returns target, current exact contribution sum, `reached`, and ETag. |
| `PUT /api/v1/finance/goals/{id}` | Conditionally changes name, target, and target date. |
| `POST /api/v1/finance/goals/{id}/contributions` | Conditionally appends `{ amountMinor, sourceTransactionId? }`, advances the goal version, and returns progress. |
| `GET /api/v1/finance/goals/{id}/contributions?page=0&pageSize=50` | Returns a bounded immutable contribution page in deterministic append order. |

An optional source posting must be local to the caller, use the goal's exact currency, and cover at
least the contribution amount. It is a trace reference only—there is no automatic conversion or
bank-account reconciliation—and it may be linked to one target only once. Goal progress is the
overflow-checked sum of immutable contribution rows.

## Persistence and operations

Production migration
[`V1__create_finance_service_schema.sql`](../../services/finance-service/src/main/resources/db/migration/V1__create_finance_service_schema.sql)
owns all finance tables, validation constraints, owner/tenant indexes, PostgreSQL interval
exclusion, idempotency snapshots, and audit rows. H2 uses the separately maintained equivalent in
`services/finance-service/src/test/resources/db/migration-h2` because H2 cannot prove the
PostgreSQL-specific exclusion implementation.

The service uses Java virtual threads. Direct traffic is bounded by the 64 KiB default body cap, a
128-request non-waiting semaphore, and a 10-second Tomcat upload/socket deadline. Identity calls
have connection/read timeouts and a fair, bounded 32-permit outbound semaphore. Deployments must
set `FINANCE_DATASOURCE_URL`, `FINANCE_DATASOURCE_USERNAME`, `FINANCE_DATASOURCE_PASSWORD`,
`FINANCE_IDEMPOTENCY_SECRET`, `FINANCE_AUDIT_CLIENT_FINGERPRINT_SECRET`, and
`IDENTITY_FINANCE_WORKLOAD_TOKEN`; no production datasource or secret default exists in source.

Actuator exposes health/liveness/readiness, info, and Prometheus on the loopback management port.
ECS logs, Micrometer Prometheus metrics, and OpenTelemetry export are configured. In particular,
`finance.idempotency.deadlock.retries` and `finance.idempotency.deadlock.exhausted` expose the
bounded PostgreSQL exclusion-check retry path without including account, tenant, category, or key
values. SQLSTATE `40P01` retries the full completion transaction up to three total attempts with
positive exponential full-jitter delays (25 ms and 50 ms caps); an exhausted transient remains
retryable through the original idempotency key and is never mislabeled as a business overlap.
A deployment still needs gateway routing, TLS/mTLS, secret management, network policy,
backups, alerting, and a telemetry backend.
