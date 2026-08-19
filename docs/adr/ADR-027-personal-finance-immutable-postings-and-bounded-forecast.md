# ADR-027: Use immutable Finance postings, integer minor units, and bounded non-persisting forecasts

**Status:** Accepted

## Context

FR31–FR36 require budgets, transactions, categories, insights, forecasts, and financial-goal
progress. Currency and financial data demand a model that does not introduce rounding errors,
retroactively rewrite transactions, imply an external bank integration, or produce a precise-looking
forecast from sparse data. Concurrent budget writes must not both admit the same category interval.

## Decision

- Store all monetary amounts as positive signed-64-bit integer minor units plus an ISO 4217 code.
  A transaction's immutable `INCOME` or `EXPENSE` direction determines its sign in aggregate views;
  neither database nor API uses floating point or decimal currency conversion.
- Treat financial transactions as immutable postings. Category correction changes only
  `current_category` and appends an immutable correction-history row in the same transaction. The
  original currency, amount, direction, date, merchant, and initial category remain fixed.
- Use versioned budget and financial-goal representations with strong ETags. All writes have
  durable actor/tenant/operation-scoped idempotency reservations that HMAC raw keys and canonical
  request values, then commit an immutable response snapshot atomically with the mutation.
- Prevent category-period budget overlaps by a PostgreSQL `btree_gist` exclusion constraint over
  owner, personal tenant, category, and inclusive `daterange`. The application prechecks too for
  quick deterministic feedback and its H2 test migration; PostgreSQL remains the final concurrent
  admission control. Before the precheck, PostgreSQL transactions acquire a transaction-scoped
  advisory lock for the owner/tenant/category scope. This serializes competing exclusion-index
  checks, avoids SQLSTATE `40P01` deadlocks, and leaves the exclusion constraint authoritative;
  non-PostgreSQL test databases skip only the advisory-lock optimization.
- Keep goals and contributions in Finance. Contributions are immutable, optionally link one local
  same-currency posting, and advance the goal version. Progress is an overflow-checked exact sum;
  no source transaction is assumed to mean an automatic transfer.
- Build insights over an explicit currency and a 1–366 day bounded window. Build forecasts only in
  memory from the preceding 52 completed weeks and require 8 observed completed weeks. Use
  nearest-rank p25/p50/p75 integer weekly statistics, disclose the source range/method, and return
  unavailable rather than made-up numeric estimates for sparse or oversized source windows.
- Do not add a bank, payment, FX, Kafka, or AI-provider dependency. The service is an internal
  ledger for user-entered postings; `NO_FX_CONVERSION` is part of each analytic/forecast contract.

## Consequences

The model makes every accepted money value representable exactly and lets a user explain how a
category changed without losing the original record. The range exclusion permits unrelated
categories and accounts to proceed concurrently while refusing unsafe overlapping scopes. The
forecast is deliberately conservative: absent history and high-volume truncation reduce output,
not confidence.

The first release does not reconcile balances, import transactions, support transfers, distribute
household finances, calculate tax, or choose exchange rates. Future external integrations must
introduce a separately reviewed source/reconciliation model, credential boundary, consent model,
and FX provenance; they cannot silently mutate existing postings or forecasts.

## Verification

`FinanceForecastServiceTest` covers completed-week boundaries, nearest-rank exact integer output,
insufficient history, and oversized source refusal. `FinanceServiceIntegrationTest` and
`FinanceControllerContractTest` cover H2/Flyway mapping, immutable replay snapshots, correction
history, ETags, overlap cleanup, contribution progress, and cross-account response equivalence.
`FinancePostgresIntegrationTest` uses PostgreSQL Testcontainers for concurrent matching retry
convergence and distinct-key overlapping-budget admission; the latter exercises the advisory-lock
path and asserts one success plus one deterministic overlap rejection.
