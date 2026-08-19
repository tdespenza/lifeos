# ADR-046: Keep local media processing opt-in and fail closed

## Status

Accepted for the development foundation; production worker deployment remains required.

## Context

The Media control plane can validate and store a source video, but it previously had no way to
produce private HLS artifacts without inventing a successful external worker result. That left
the local foundation unable to exercise the HLS lifecycle while production still needs an
isolated worker, object store, reconciliation, and deployment controls.

## Decision

Add an explicit `MEDIA_PROCESSING_MODE=LOCAL_DEVELOPMENT` adapter that invokes a deployment-owned
`ffmpeg` executable with discrete `ProcessBuilder` arguments. The adapter is asynchronous and
bounded by a two-job semaphore, a 60-second process deadline, a merged diagnostic output cap,
generated source/output paths, and strict manifest/segment validation before atomic promotion.
Successful validation is the only path to `HLS_READY`; missing binaries, malformed output,
timeouts, and process failures produce a safe `PROCESSING_FAILED` state. The default remains
`EXTERNAL_WORKER_REQUIRED`, and the production object-store boundary cannot select the local
adapter.

## Consequences

- Developers can exercise a real source-to-HLS lifecycle when a reviewed local ffmpeg binary is
  installed, without weakening production startup defaults.
- Source paths and ffmpeg diagnostics never enter API responses, logs, or durable metadata.
- Processing is eventually consistent: upload returns the stored source state and HLS becomes
  readable only after the bounded worker succeeds.
- Recording capture, WebRTC/SFU integration, production object storage, completion events, and
  orphan reconciliation remain separate deployment work.
