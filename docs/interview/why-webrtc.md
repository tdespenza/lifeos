# Why WebRTC for live video + HLS for recorded playback?

The repository now has a secure media control-plane foundation: owner-scoped asset/session metadata,
bounded multipart source upload, and private HLS manifest/segment read contracts. There is still no
SFU, transcoding worker, recording pipeline, or deployed object store; those external adapters fail
closed. ADR-012 remains the rationale for the future live/playback transport split.

The reasoning starts from a simple observation: video journaling and coaching in LifeOS actually need two completely different things. A live coaching call or a live video-journal session needs sub-second latency — it's a real conversation, and anything that feels laggy breaks the interaction. But watching a recording of that session afterward has completely different requirements: I want it to scrub, seek, adapt its bitrate to whatever device I'm on, and ideally get served off a CDN or object storage instead of tying up a real-time media server. Those are opposite optimization targets, and no single protocol is good at both.

So the plan is to use each protocol for what it's actually built for. WebRTC — over an SFU, something in the mediasoup/LiveKit style — handles live rooms, because its UDP/SRTP path with congestion control is the right transport for that job; sub-500ms glass-to-glass latency is the design target I'm building toward, not a number WebRTC hands you automatically — actual latency still depends on the SFU implementation, network path, and encoding settings, and it's something to measure once there's a real pipeline (see `docs/benchmarks/`), not assert ahead of building it. Once a session ends, the recording gets transcoded to HLS and stored in S3/MinIO, so playback becomes a standard segmented-HTTP experience: cacheable, adaptive bitrate, trivially seekable, and consistent across the three planned clients — Angular web, JavaFX desktop, Flutter mobile — instead of needing three different real-time playback integrations.

I did consider just picking a managed platform like Twilio Video or Daily.co and letting them own the SFU and recording pipeline entirely. Honestly, for a startup trying to ship fast, that's the right call. But this project's whole point is to demonstrate that I can design and operate real streaming infrastructure, so offloading exactly the subsystem I'm trying to prove I understand didn't make sense here.

The cost of this decision is real, and I don't want to undersell it: it's two media pipelines instead of one, a recording-to-playback handoff gap that has to be modeled as an async, event-driven pipeline rather than something instantaneous, and likely real transcoding (not just remuxing) between WebRTC's codecs and what HLS expects. If this were a startup racing to ship, I'd revisit the managed-SDK path. For what this project is trying to be, I think owning it is the right tradeoff — I just haven't built any of it yet.

Relevant ADRs: [ADR-012](../adr/ADR-012-use-webrtc-and-hls-for-video.md)
