# ADR-012: Use WebRTC for live sessions and HLS for recorded playback

## Context

The Media Streaming Service supports two structurally different workloads: (1) live, bidirectional coaching and video-journaling sessions where a user (and possibly an AI participant or coach) needs sub-second, interactive audio/video exchange, and (2) on-demand playback of the recorded session afterward, plus downstream processing (transcription, AI summary, action-item extraction, blockchain anchoring of the summary hash). These two workloads have opposite optimization targets: the live path optimizes for latency and interactivity at low concurrency per room; the playback path optimizes for delivery efficiency and scale-out to many viewers/devices (web, desktop via JavaFX, mobile via Flutter) with tolerance for multi-second startup delay. No single transport is well-suited to both.

## Options Considered

1. **WebRTC only (peer-to-peer/SFU, including for playback)** — Reuse the live transport to also serve recorded playback by replaying the session through an SFU. Rejected because WebRTC has no native scalable distribution model for on-demand content: it relies on per-viewer real-time streams or SFU fan-out, doesn't leverage HTTP caching/CDNs, has no standard seek/scrub or adaptive-bitrate-over-HTTP model, and forces every playback view to pay real-time transport cost for content that isn't time-sensitive.

2. **HLS only (including for live sessions)** — Use segmented HTTP streaming for the live rooms too. Rejected because HLS's segment-buffer design imposes several seconds to tens of seconds of glass-to-glass latency (even with LL-HLS), and it is fundamentally a one-way distribution protocol — it cannot support bidirectional, multi-party, low-latency interaction, which coaching sessions and live video journaling require.

3. **Third-party video platform SDK (Twilio Video, Daily.co, Agora)** — Adopt a managed real-time video platform for both live rooms and recording/playback. Rejected for this project: it would cut integration effort dramatically and offload SFU/TURN operations and scaling, but it (a) introduces a recurring paid external dependency and vendor lock-in for a portfolio system whose stated purpose is to demonstrate engineering depth, and (b) replaces the exact subsystem — media transport, SFU/room management, recording pipeline, HLS packaging — that this project exists to show mastery of in FAANG-style system design terms. It remains the pragmatic choice for a startup optimizing time-to-market, which is explicitly not this project's constraint.

4. **WebRTC for live + HLS for recorded playback (chosen)** — Use each protocol for the workload it was designed for, with a recording/transcoding step (media-streaming-service consumes the WebRTC session, records to a mezzanine format, then packages to HLS) bridging the two.

## Decision Made

Use WebRTC (via an SFU, e.g., mediasoup/LiveKit-style architecture) for all live, interactive sessions — coaching calls and live video journaling — and transcode recordings to HLS (via ffmpeg-based segmentation, stored in S3/MinIO) for all post-session playback, transcription, AI summarization, and archival access.

## Why

- **Latency requirements differ by an order of magnitude.** Live sessions need sub-500ms glass-to-glass latency for natural conversation; WebRTC's UDP/SRTP media path with congestion control (via a Selective Forwarding Unit) achieves this. Playback has no such requirement — a 3–10 second startup delay is imperceptible for a user reviewing a past session.
- **Distribution model matches consumption pattern.** Live sessions are small-fanout (1–few participants) and session-scoped; HLS's segment-and-manifest model is built for exactly the opposite — cacheable via CDN/object storage, adaptive bitrate per device (important given three heterogeneous clients: Angular web, JavaFX desktop, Flutter mobile), and trivially seekable/scrubbable, none of which WebRTC provides natively.
- **Each protocol is battle-tested for its role**, avoiding the engineering cost of forcing one protocol to do a job it wasn't designed for (e.g., building custom seek/ABR on top of WebRTC, or building a custom low-latency signaling layer on top of HLS).
- **Keeps the build-vs-buy tradeoff intentional.** Rejecting a managed SDK (option 3) is a deliberate cost: more implementation surface (SFU operations, TURN/STUN, recording pipeline, HLS packaging) in exchange for demonstrable ownership of the streaming-systems stack, which is a stated project goal.

## Tradeoffs

- **Two media pipelines to build and operate** instead of one: SFU/TURN infrastructure for WebRTC (NAT traversal, bandwidth estimation, simulcast) plus a separate transcoding/packaging pipeline for HLS (segment duration, keyframe alignment, multi-bitrate ladder). This roughly doubles the operational surface of the streaming-systems.
- **A handoff gap between "live" and "recorded."** The recording must be finalized and transcoded before HLS playback is available, so there is an unavoidable post-session processing window (recording flush → mezzanine encode → HLS segment/manifest generation) before summary, transcription, and playback are ready; this must be modeled as an explicit async pipeline (event-driven via Kafka/Pulsar) with status states, not treated as instantaneous.
- **No shared codec/packaging assumptions.** WebRTC typically runs VP8/VP9/Opus or H.264/Opus over SRTP; HLS output typically needs H.264/AAC in fragmented MP4 or TS segments. Transcoding (not simple remuxing) may be required, adding CPU cost and a potential quality/latency tradeoff in the recording pipeline.
- **We are self-operating what a managed SDK would abstract away**, including TURN server capacity planning, SFU scaling under concurrent room load, and recording storage lifecycle — real operational burden accepted in exchange for the portfolio/learning goal.

## Consequences

- media-streaming-service owns two distinct subsystems: a real-time SFU/signaling component and a batch transcoding/packaging component, connected by an event (e.g., `session.recording.completed`) that triggers the HLS conversion, transcription, and AI-summary jobs.
- Client apps (Angular, JavaFX, Flutter) each need a WebRTC client integration for live rooms and a separate HLS player integration for playback — two client-side media stacks per platform instead of one.
- Session recordings become a first-class artifact with their own storage lifecycle (S3/MinIO), retention policy, and — per requirements — an optional blockchain-anchored hash of the AI summary, which depends on the HLS/transcription pipeline completing successfully.
- Capacity planning is bifurcated: live-session capacity is bounded by concurrent SFU room/participant count and bandwidth; playback capacity is bounded by CDN/object-storage egress and transcoding throughput — these must be monitored and scaled independently.

## When This Decision Would Be Wrong

This split would be wrong to maintain if the product pivoted toward **near-live playback of recordings** (e.g., a coach reviewing a session moments after it ends and wanting frame-accurate low-latency access), which would push toward LL-HLS or WebRTC-based playback instead of standard HLS. It would also be wrong if session concurrency grew to a scale (hundreds of simultaneous live rooms) where operating a custom SFU/TURN fleet became a genuine reliability and cost burden disproportionate to the project's learning goals — at that point, re-evaluating a managed SDK (Twilio/Daily/Agora) for the live path specifically, while keeping the self-built HLS pipeline for playback/portfolio value, would be the right correction. Finally, if the team composition shifted from a single engineer/small team to a context where nobody can own SFU operations, the managed-SDK alternative should be revisited.

## How We Will Validate It

- **Live-path latency benchmark:** measure glass-to-glass latency (camera capture to remote render) under the SFU across a 2-participant and an 8-participant room using k6/custom WebRTC test harness; target p95 < 500ms on a representative broadband/Wi-Fi network, alerting via OpenTelemetry/Grafana if p95 exceeds 800ms in staging load tests.
- **Recording-to-playback pipeline SLA:** measure time from `session.ended` event to HLS manifest availability; target p95 < 90 seconds for a 30-minute session, tracked as a Prometheus histogram and validated under a k6 load test simulating 20 concurrent session completions.
- **Playback startup and rebuffer rate:** instrument the HLS players in each client to report time-to-first-frame (target p95 < 3s on a 5 Mbps connection) and rebuffer ratio (target < 1% of playback time); ship as client-side metrics through OpenTelemetry to catch regressions in the ABR ladder or CDN/object-storage configuration.
- **SFU capacity test:** load-test the SFU with simulated concurrent rooms (ramping to the expected peak, e.g., 50 concurrent rooms) to establish the CPU/bandwidth-per-room cost curve, which directly informs whether the "operate our own SFU" tradeoff in this ADR remains sound as usage grows — a documented input to the revisit trigger above.
