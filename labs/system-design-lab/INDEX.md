# System Design Lab Catalog

These are self-contained design exercises, not deployed LifeOS services. Their numbered limits are
starting assumptions that make tradeoffs discussable and testable in an interview or design review.

| Mini-system | Primary design question | Exercise |
| --- | --- | --- |
| URL shortener | How can redirect reads stay fast while link mutation and abuse controls remain correct? | [URL shortener](systems/url-shortener.md) |
| Notification system | How can one accepted notification become bounded, private, observable delivery attempts? | [Notification system](systems/notification-system.md) |
| Search engine | How can a searchable projection remain useful while its source data changes or is deleted? | [Search engine](systems/search-engine.md) |
| Distributed scheduler | How can due work run at-least-once without duplicate business effects? | [Distributed scheduler](systems/distributed-scheduler.md) |
| Recommendation engine | How can ranked candidates be personalized without making a stale model authoritative? | [Recommendation engine](systems/recommendation-engine.md) |
| Rate limiter | How can shared limits be fair and reliable during a coordination-store outage? | [Rate limiter](systems/rate-limiter.md) |
| Chat and messaging | How can conversations preserve per-stream order through reconnects and fanout pressure? | [Chat and messaging](systems/chat-messaging.md) |
| Video session | How can signaling and media-session state recover without treating media relays as durable state? | [Video session](systems/video-session.md) |
| Document storage | How can opaque binary storage, metadata transactions, and lifecycle controls remain separately correct? | [Document storage](systems/document-storage.md) |
| Event analytics | How can replayable ingestion create useful aggregates without losing lineage or privacy controls? | [Event analytics](systems/event-analytics.md) |

All ten entries use [the reusable template](template.md). Run `bash labs/system-design-lab/verify.sh`
after changing the catalog or an exercise.
