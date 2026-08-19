# ADR-051: Privacy-Safe Identity Recovery Notification Outbox

## Status

Accepted for the repository-owned recovery boundary; external delivery remains deployment work.

## Context

Passkey recovery has durable security audit facts, but a recovery-code generation or recovery login
could not reach Notification without coupling Identity's authentication transaction to a provider or
making a dual database/Kafka write. Raw recovery codes are secrets and must never enter an event,
log, or notification payload.

## Decision

Identity writes a generic `NotificationRequestedV2` CloudEvent to its own transactional outbox in
the same transaction as the recovery mutation. The event is keyed by account UUID and carries only
bounded security copy: that recovery codes were generated, or that a recovery code was used. It
contains no email address, raw code, token, client address, or credential material. A bounded
lease-based relay publishes the immutable payload to `lifeos.notification.requested.v2` with
idempotent Kafka producer settings, exponential full-jitter retry, and a durable dead-letter row.

The relay is deployment-configurable and test profiles disable it. Notification remains the owner of
endpoint resolution and provider delivery; provider credentials, broker ACLs, retention, and delivery
evidence are not fabricated by this ADR.

## Consequences

- Recovery security communications survive an Identity transaction commit and relay restarts.
- At-least-once publication is safe because the CloudEvent ID is the durable idempotency key.
- A Kafka outage leaves a visible pending/retry/dead-letter state rather than silently dropping the
  communication.
- External email/push configuration is still required before users receive a message.
