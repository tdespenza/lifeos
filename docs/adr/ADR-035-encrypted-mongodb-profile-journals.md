# ADR-035: Encrypted MongoDB storage for profile journals and notes

Status: accepted for the bounded local foundation; production enablement remains deployment-owned.

The Profile service now exposes owner-scoped journal/notes endpoints behind an explicit MongoDB
feature flag. PostgreSQL remains the system of record for structured profile and household state;
MongoDB owns only flexible journal content. The adapter encrypts title and content with AES-256-GCM
before persistence, uses the account UUID as its sole lookup scope, enforces bounded content and
per-owner entry counts, and uses deterministic owner/key IDs plus version checks for replay-safe
mutations. Missing, cross-owner, stale, or conflicting entries never disclose whether another
account owns a matching document.

The default is disabled and fail-closed. The local Compose `mongo` profile is deliberately
loopback-only and unauthenticated development scaffolding. A production deployment must supply
authenticated TLS MongoDB, managed key rotation, retention/backups, consent UX, and network policy;
none of those are implied by this code-owned boundary.
