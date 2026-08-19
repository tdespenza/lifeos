# ADR-043: Expose the bounded Analytics projection over optional gRPC

## Status

Accepted — implemented as an opt-in host.

## Context

The Analytics service already owns the privacy-minimized dashboard snapshot and daily metric
history. Internal consumers need a versioned, workload-authenticated transport without forwarding
an end-user bearer token or requiring the gateway to read Analytics' database. The generated
`lifeos.analytics.v1` contract was present but had no service implementation.

## Decision

Analytics exposes `DashboardAggregationService/GetDashboardSnapshot` on an optional mTLS gRPC
listener (default port `10091`). A workload metadata interceptor performs constant-time token
comparison after mTLS. The request carries only an owner UUID and correlation metadata; the service
derives the personal tenant, reads the bounded default-period snapshot, and returns at most 100
metric rows. The host is disabled by default and fails closed unless all certificate, trust-bundle,
and workload-token settings are supplied.

## Consequences

- Internal consumers can use the generated contract without crossing a REST/database boundary.
- The transport is bounded and owner-scoped, but it is not a replacement for the authenticated
  public Analytics REST and GraphQL boundaries.
- Certificate rotation, service discovery, and production mesh rollout remain deployment work.
- The projection intentionally omits raw event payloads, arbitrary tenant selectors, and unbounded
  history reads.
