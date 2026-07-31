# ADR-014: Use JavaFX + GraalVM Native Image for the desktop client

## Context

LifeOS ships three clients — Angular (web), Flutter (mobile), and a desktop client — against the same set of Spring Boot microservices. The desktop client needs to run on Windows, macOS, and Linux, support offline-capable local workflows (task/goal management, document vault access, calendar), and integrate with OS-level features (file system, notifications, secure credential storage). Beyond the functional requirements, this project exists as a FAANG-grade engineering portfolio: the technology choices are expected to demonstrate depth, not just deliver a working UI. The rest of the stack (identity, profile, task/goal, calendar, finance, document vault, AI orchestrator, algorithm engine) is Java 25 on virtual threads with structured concurrency, so the desktop client's language and runtime choice is also a statement about how consistently the project applies its own stated engineering standards.

## Options Considered

- **Electron (Chromium + Node.js, reusing the Angular codebase)**: fastest path to a shippable desktop app and lets us reuse Angular components directly. Rejected because it ships a full Chromium + V8 runtime (100–150MB+ baseline, per-app memory duplication), and it contributes nothing to the project's Java/JVM engineering narrative — for a portfolio meant to demonstrate desktop and native-image skill, Electron is a step backward, not a shortcut.
- **Compose for Desktop / Kotlin Multiplatform**: modern, JVM-based, good tooling, and closer in spirit to JavaFX than Electron is. Rejected because it shifts a meaningful fraction of the codebase to Kotlin, diluting the project's Java-centric positioning and creating a second JVM language to maintain interop and hiring-signal consistency for.
- **Native Swift (macOS) / C++ (cross-platform) desktop app**: best possible native performance and OS integration. Rejected outright — it demonstrates zero Java or JVM skill, requires maintaining a second (or third, per-OS) codebase with no code sharing against the backend, and does not serve the project's goal of proving Java engineering depth.
- **JavaFX + GraalVM Native Image (chosen)**: keeps the client on the JVM, lets desktop-specific code share DTOs/validation logic with the Spring services, and produces a native binary via GraalVM rather than shipping a JVM runtime alongside the app.

## Decision Made

Build the LifeOS desktop client with JavaFX on Java 25, compiled ahead-of-time to a platform-native executable using GraalVM Native Image.

## Why

JavaFX is the only actively maintained, full-featured native-UI toolkit on the JVM, which keeps the desktop client inside the same language and type system as the rest of LifeOS — model classes, validators, and client-side algorithm-engine logic can be shared with backend modules without a serialization or FFI boundary. GraalVM Native Image removes the two biggest practical objections to a JVM desktop app: cold-start latency and the requirement to bundle or require a JRE. A native-image build starts in tens of milliseconds instead of the ~500ms–1s typical of a warming JVM, and produces a single self-contained binary suitable for OS-native installers (MSI, DMG, deb/rpm). For a project whose explicit purpose is to demonstrate Java/JVM engineering depth, shipping a JVM desktop app that starts as fast as a native one is a much stronger signal than defaulting to Electron.

## Tradeoffs

- **Reflection and dynamic class loading are constrained.** GraalVM's closed-world static analysis needs explicit reachability metadata (`reflect-config.json`, proxy/resource/JNI configs) for anything not resolvable at build time. JavaFX's FXML loader relies on reflection to instantiate controllers and inject `@FXML` fields, so every FXML-backed view needs generated or hand-maintained reflection config, and the config must be regenerated (via the GraalVM tracing agent) whenever the view graph changes — a real, recurring build-maintenance cost, not a one-time setup step.
- **Shared code from Spring-based modules is not free.** Spring's runtime relies heavily on reflection, dynamic proxies, and classpath scanning for DI and serialization — none of it native-image-safe. Any backend module we want to reuse from the desktop client (validation logic, DTOs, algorithm-engine code) must be reused as plain Java with no Spring annotations in the shared module, or isolated behind a build that excludes Spring from the native-image classpath entirely. We cannot casually `implementation project(":task-service-core")` and expect it to build.
- **Build times and toolchain fragility increase.** Native-image compilation is substantially slower than a normal `javac` build (minutes, not seconds) and is sensitive to GraalVM version drift across the three target OSes, meaning CI must build and test on all three platforms rather than cross-compiling from one.
- **Debugging is harder post-compilation.** Native binaries lose standard JVM tooling (attach-based profilers, hot code reload); most iteration has to happen on the JVM (`jlink`/regular execution) with native-image builds reserved for release candidates and periodic compatibility checks.

## Consequences

- We take on a native-image reflection/resource config file per module that must be kept current as JavaFX views and shared DTOs evolve; this becomes part of the PR review checklist for desktop-client changes.
- CI needs a three-OS native-image build matrix (Windows/macOS/Linux), adding pipeline time and GraalVM-version pinning as an explicit maintainability concern.
- Desktop installers become straightforward to produce (via `jpackage` wrapping the native binary), giving us real MSI/DMG/deb artifacts for release engineering — a concrete, demoable operational-readiness story for the portfolio.
- Any module intended for desktop reuse must be written and reviewed as Spring-free plain Java from the start, which is a constraint we need to document in module-boundary guidelines, not discover during a native-image build failure.
- We forgo the much larger Electron/web-tech contributor pool and tooling ecosystem in exchange for JVM-consistency and the native-image skill signal the project is explicitly optimizing for.

## When This Decision Would Be Wrong

This choice should be revisited if the desktop client's UI requirements grow to need heavy reuse of Angular components or web-rendering capability (e.g., embedding the same rich WebRTC/HLS media views used on web) — at that point JavaFX's lack of a built-in web-tech bridge (beyond the limited `WebView`/WebKit component) would force either duplicated UI work or an embedded browser anyway, eroding the case against Electron. It would also be wrong if the desktop client needed to be built and maintained by contributors without JVM/reflection-config experience — the native-image maintenance burden assumes a Java-fluent team; if the desktop surface were handed to a small team optimizing purely for shipping speed rather than the Java-skill signal, Compose for Desktop or Electron would be faster to iterate on. Finally, if GraalVM Native Image support for a future JavaFX or Spring version regresses or lags significantly (tracked via their release compatibility notes), we would need to fall back to a standard JVM distribution (`jlink` custom runtime) rather than native-image, trading startup latency back for build stability.

## How We Will Validate It

Before GA, we will benchmark cold-start latency of the native-image binary against a `jlink`-packaged JVM build of the same JavaFX app on each target OS, with a target of native-image start-to-interactive under 150ms (p95) versus a documented JVM baseline. We will track native-image build success as a CI gate — any reflection-config drift that breaks a native build fails the pipeline, not just a warning — and measure build time itself, flagging regressions past a 5-minute p95 per-OS build budget. Installer size will be tracked per platform (MSI/DMG/deb) with a target under 80MB, benchmarked against an equivalent Electron packaging of the same feature set to keep the "no Chromium tax" claim empirically honest rather than assumed.
