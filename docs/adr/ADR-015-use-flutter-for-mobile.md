# ADR-015: Use Flutter for iOS and Android mobile clients

## Context

LifeOS ships three client surfaces on top of the Spring Boot backend: Angular (web), JavaFX + GraalVM Native Image (desktop), and mobile (iOS + Android). The mobile clients need real iOS support (not a PWA wrapper), production-quality UI performance, and a maintainable path to feature parity with web over time. The project's stated differentiation budget is backend/Java engineering — virtual threads, structured concurrency, microservices, event streaming, blockchain-backed integrity proofs — not mobile platform depth. Mobile is a required, real client, but it is explicitly not the surface this project is trying to win interviews on. The team is effectively a single engineer (or a very small team) who cannot sustain two full native codebases in parallel with backend and desktop work. This decision was already made in REQUIREMENTS.md; this ADR records the reasoning behind it.

## Options Considered

1. **Native Swift (iOS) + Kotlin (Android)** — best possible platform fidelity, performance, and access to platform APIs (biometrics, widgets, background execution) with zero abstraction tax. Rejected because it doubles mobile engineering effort (two languages, two UI toolkits, two release pipelines, two sets of platform bugs) for a project whose primary skill signal is backend/Java, not mobile. That effort is better spent deepening the microservices, concurrency, and data layer.
2. **React Native** — large ecosystem, mature tooling, big hiring pool. Rejected because LifeOS's web client is Angular, not React; RN would not share components, state patterns, or team mental model with the web app the way it would in a React-first shop. It would be a third UI paradigm with no cross-client leverage, without buying the platform fidelity of going fully native.
3. **Kotlin Multiplatform Mobile (KMM)** — attractive in principle: shares a JVM/Kotlin mental model with the Java backend, and can share business logic (networking, models, validation) across iOS and Android while still using native UI (SwiftUI/Jetpack Compose). Rejected primarily because full-UI KMM (Compose Multiplatform for iOS) is materially less mature than Flutter's widget/rendering stack as of this decision — smaller community, fewer production references, higher risk of hitting unpaved paths on iOS specifically, which is the platform this decision most needs to de-risk.
4. **Flutter (chosen)** — single Dart codebase rendering to both platforms via its own engine, mature widget system, strong iOS support, large community and job-market presence, and a UI performance profile (compiled, GPU-composited, no JS bridge) that avoids the historical RN perf ceiling.

## Decision Made

Use Flutter for both the iOS and Android LifeOS clients, communicating with the backend over the same REST/GraphQL contracts used by other clients (gRPC where a mobile-specific low-latency path is justified).

## Why

Flutter is the option that best satisfies the actual constraint set: it delivers real, App-Store-quality iOS support (unlike PWA/web-wrapper approaches), collapses two platform codebases into one so a small team can actually maintain it, and has UI performance and tooling maturity that neither React Native (JS bridge overhead, historically weaker iOS parity) nor KMM's full-UI story (less proven, smaller ecosystem) currently match. Choosing it also protects the project's stated focus: engineering time saved on mobile is time spent on the backend, algorithms, and distributed-systems work that is the actual portfolio thesis. Flutter's job-market visibility is a secondary but real benefit for a portfolio project.

## Tradeoffs

- **Language fragmentation**: Dart is a fourth language in the stack (Java, TypeScript/Angular, Dart, plus SQL/config), with zero code or type sharing with the Java backend — API contracts must be kept in sync manually or via generated clients (OpenAPI/GraphQL codegen), not shared DTOs.
- **Platform-channel tax**: anything requiring deep native integration (custom biometric flows, background sync, HealthKit-style APIs, native widgets, camera edge cases) goes through Flutter platform channels, which is slower to build and debug than calling the native API directly, and is the most likely source of iOS/Android behavioral divergence.
- **No cross-client UI reuse**: because web is Angular, none of the component/state work in Flutter transfers to web or vice versa — three client stacks (Angular, JavaFX, Flutter) is more surface area to keep current with backend contract changes than a two-stack setup would be.
- **App size and startup**: Flutter's bundled engine increases binary size and cold-start time versus fully native apps, which matters for a personal-data app where users expect instant open.

## Consequences

- Mobile ships as one codebase and one release cadence instead of two, which is what makes it feasible for a small team to keep mobile in sync with rapid backend/API evolution.
- The project gains genuine iOS and Android artifacts (real portfolio evidence of a working mobile client) without consuming the engineering time budgeted for the backend differentiation work.
- API contracts (REST/GraphQL schemas, error formats, pagination conventions) become a harder boundary that must be explicit and versioned, since no shared Java types exist between backend and mobile the way they might with a JVM-based mobile stack.
- Any future feature requiring deep native platform capability (e.g., iOS widgets, CallKit, on-device ML tightly coupled to platform frameworks) will incur extra platform-channel implementation cost that a native app would not.

## When This Decision Would Be Wrong

This choice should be revisited if either of two things happens: (1) the project's differentiation goal shifts — e.g., LifeOS pivots toward being primarily a mobile-engineering portfolio piece, or a specific job target requires demonstrated native iOS/Android depth — at which point native Swift/Kotlin becomes the right investment; or (2) LifeOS mobile usage grows to require deep, continuous native platform integration (background location, complex widgets, native ML pipelines, offline-first sync with conflict resolution at native-storage level) such that platform-channel overhead becomes the dominant maintenance cost rather than an occasional cost. A team-size change (e.g., dedicated iOS and Android engineers joining) would also weaken the "one codebase for a small team" rationale and make native or KMM worth re-evaluating.

## How We Will Validate It

- **Cold start**: measure time-to-interactive on a mid-tier physical device (not simulator) for both platforms; target under 2.5s on Android (mid-range, e.g., Pixel 6a class) and under 2.0s on iOS (iPhone 12 class), tracked via Firebase Performance or equivalent in CI-adjacent smoke tests.
- **Frame stability**: track dropped-frame rate on primary scroll/list views (task list, calendar, media feed) using Flutter DevTools' performance overlay; target 99th-percentile frame build+raster time under 16ms (60fps) on the same reference devices, re-measured after each Flutter SDK upgrade.
- **Platform-channel surface area**: track the count and LOC of native platform-channel implementations quarterly; if this exceeds roughly 15% of total mobile module code, treat it as a signal that native integration needs outweigh the cross-platform benefit and re-open this ADR.
- **Store acceptance and crash-free rate**: monitor crash-free session rate (target ≥ 99.5%) via Crashlytics/Sentry post-launch; a sustained gap between iOS and Android crash-free rates would indicate the "real iOS support" premise of this decision isn't holding.
