# ADR-048: Bounded Java CLI proof helper

## Context

The Java 25 requirement includes CLI tooling, while Trust Ledger proof requests must never receive
raw document bytes from an untrusted or ad-hoc client. A local helper should therefore produce a
digest without uploading content, retaining it, or allocating memory proportional to file size.

## Decision

Add `cli:lifeos-cli` as a plain Java 25 application. Its `hash <file>` command accepts only regular
files up to 64 MiB, streams them through JCA SHA-256 with a fixed 32 KiB buffer, and emits one JSON
object containing the algorithm, byte count, and lowercase digest. It has no network, credential,
database, or service dependency. The `version` command is informational.

## Consequences

- The CLI is independently testable and packageable with Gradle's application plugin.
- Hashing is O(n) time and O(1) additional memory relative to input size.
- A user still submits the digest through the authenticated Trust Ledger API; the CLI does not
  claim blockchain anchoring or proof confirmation.
- The 64 MiB bound prevents accidental unbounded local resource consumption.
