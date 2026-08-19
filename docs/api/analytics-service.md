# Analytics service

The analytics service is a bounded, privacy-minimized read model. It stores only metric keys and
numeric aggregates keyed by the authenticated account and personal tenant; it does not persist
raw notification bodies, destinations, tokens, or event payloads.

Every direct-service connector applies `ANALYTICS_INBOUND_REQUEST_TIMEOUT` (10 seconds by default)
to connection, keep-alive, and upload reads. Requests that do not complete within that bound are
terminated by the embedded Tomcat connector before they can retain service resources.

`GET /api/v1/analytics/dashboard?periodDays=1..90` and
`GET /api/v1/analytics/insights?periodDays=1..90` are reachable through the gateway. The gateway
adds the authenticated account/session headers and an HMAC proof over method, path, account, and
session. Direct calls without that proof fail closed. Dashboard values are deterministic snapshots;
insights derive only from available bounded metrics, return an empty list when evidence is missing,
and never invoke an AI provider or persist recommendations. `POST /api/v1/analytics/internal/metrics`
is workload-token protected and is intended for bounded service-side metric ingestion.

`GET /api/v1/analytics/trends?metricKey=<key>&periodDays=1..90&days=1..90` returns an owner-scoped,
chronologically ordered daily series for one bounded metric. Each write records the latest value
for the UTC observation day in a separate history table, while the snapshot remains the fast
dashboard read model. The endpoint never performs a global count, accepts only the authenticated
personal tenant, and returns an empty series when no observations exist. Habit and finance event
producers can use the same internal metric contract without exposing raw domain data.

The AI Assistant may use `POST /api/v1/analytics/internal/assistant-insights` with its dedicated
workload identity/token and a gateway HMAC proof bound to the Identity-issued account/session. The
projection returns at most five deterministic productivity signals for a 1–90 day period; it never
returns raw event payloads or accepts a caller-supplied tenant. Missing workload credentials or an
invalid proof returns `401`.

The optional Kafka consumer reads `lifeos.notification.requested.v2`, atomically commits its
CloudEvent inbox reservation with the `notifications.requested` snapshot update, and deduplicates
replays. Kafka is disabled by default; enabling it requires broker ACLs, the versioned topic, and
the analytics workload token. The CloudEvent correlation ID is bound to the consumer thread's
MDC/ScopedValue for the projection lifetime, preserving trace correlation without retaining the
event body. Malformed records are sent to
`lifeos.notification.requested.v2.DLT` without retry; transient projection failures receive two
one-second retries before the same durable DLT route. Low-cardinality
`analytics.events.processed`, `analytics.events.duplicates`, `analytics.events.failures`, and
`analytics.events.processing_lag` metrics expose projection health and bounded lag.

An optional internal gRPC host (`ANALYTICS_GRPC_ENABLED=true`, default port `10091`) implements
`lifeos.analytics.v1.DashboardAggregationService/GetDashboardSnapshot`. It requires mTLS and the
`x-lifeos-workload-token` metadata value, derives the personal tenant from the supplied owner UUID,
and returns at most 100 default-period metric rows. It is disabled unless certificate, trust-bundle,
and workload-token settings are explicitly provisioned.

The service is independently deployable on ports 8091/9091 with isolated database
`lifeos_analytics`. Dashboard values and insights are deterministic, bounded to 90-day periods, and
returned in stable order. This is the foundation for FR75–80; bounded daily trend history now
supports FR76/FR77 reads, and deterministic AI recommendation projection is available behind a
separate workload/proof boundary, while model narratives,
complete event coverage, and client-specific visualizations remain future work.
