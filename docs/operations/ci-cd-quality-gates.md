# CI/CD quality gates

This repository runs the quality pipeline in [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).
The stages deliberately run as separate jobs so a failure names the affected engineering concern
instead of hiding it in a generic build failure. Every pull request and push runs the local,
repeatable gates below. Only deployment-capable jobs are limited to protected `dev` and `main`
pushes.

| Requirement | CI stage | Local command | Evidence retained by CI |
| --- | --- | --- | --- |
| NFR29: compile | `compile` | `./gradlew compileProject` | Gradle compile log |
| NFR30: format check | `format` | `./gradlew formatCheck` | Gradle task log |
| NFR31: unit tests | `tests` | `./gradlew test` | JUnit XML and HTML reports |
| NFR32: integration tests | `tests` | `./gradlew integrationTest` | JUnit XML and HTML reports |
| NFR33: contract tests | `tests` | `./gradlew contractTest` | JUnit XML and HTML reports |
| NFR34: static analysis | `static-analysis` | `./gradlew staticAnalysis` | Checkstyle XML and HTML reports |
| NFR35: security scan | `source-security` | `bash scripts/scan-source-security.sh` | failing scanner output |
| NFR36: mutation testing | `mutation` | `./gradlew mutationTest` | PIT XML and HTML reports |
| NFR37: Docker image build | `container` | `bash scripts/build-container-images.sh` | successful image build |
| NFR38: SBOM generation | `sbom` | `./gradlew cyclonedxBom && bash scripts/verify-sbom.sh` | CycloneDX JSON/XML artifact |
| NFR39: container scan | `container` | `bash scripts/scan-container-images.sh` | failing scanner output |
| NFR40: staging deployment | `staging-deploy` | deployment-system specific | protected-environment job log |
| NFR41: staging smoke test | `staging-smoke` | `bash scripts/staging-smoke-test.sh` | protected-environment job log |
| NFR42: publish test reports | `tests` | n/a | retained `test-reports-*` artifact |

## NFR27 test portfolio

The following portfolio is executable today. A live environment is inherently required for the
three live-environment categories, so their always-visible status jobs report **pending**
until the protected staging prerequisites below are configured; they never report a skipped test
as a pass.

| Coverage category | Executable task/job | Scope and failure behavior |
| --- | --- | --- |
| Unit | `test` | JUnit service tests fail the build on any assertion or startup failure. |
| Integration | `integrationTest` | Named database/container/Spring integration suites fail closed when no matching test exists. |
| Contract | `contractTest` | HTTP/service-boundary suites fail closed when no matching test exists. |
| End-to-end | `endToEndTest` / `end-to-end` | After staging smoke succeeds, checks readiness plus a non-mutating Gateway-to-Identity invalid-registration path and correlation-ID propagation. `end-to-end-status` explicitly reports prerequisites when it cannot run. |
| Performance | `performanceTest` / `performance` | A bounded k6 readiness test (1–100 VUs, 5–60 seconds) enforces error-rate and p95 thresholds and publishes its JSON summary. `performance-status` reports missing protected staging configuration. |
| Mutation | `mutationTest` / `mutation` | PIT creates XML and HTML mutation reports; it is intentionally explicit rather than part of local `check`. |
| Security | `source-security`, `container` | Trivy scans source, built dependencies, secrets, configuration, and runtime images for high/critical fixable issues. |
| Architecture | `architectureTest` / `architecture` | Verifies deployable-service structure, package ownership, prohibited direct cross-service imports, and non-root runtime-image invariants. |
| Chaos | `chaosTest` / `chaos` | Calls an approved staging runner that injects and rolls back a dependency-isolation failure, then verifies the participating services recover. `chaos-status` reports missing rollback-capable infrastructure. |

## Local quality workflow

The Gradle wrapper resolves the configured Java 25 toolchain. Docker-backed integration and
security checks additionally require a running Docker daemon; `jq` is needed to validate SBOM and
health-response JSON. The equivalent local sequence is:

```bash
./gradlew formatCheck compileProject staticAnalysis architectureTest test integrationTest contractTest
./gradlew mutationTest
./gradlew packageServices cyclonedxBom
bash scripts/verify-sbom.sh
bash scripts/build-container-images.sh
bash scripts/scan-source-security.sh
bash scripts/scan-container-images.sh
```

`check` includes Checkstyle plus the named integration and contract suites, but deliberately does
not include PIT. Mutation analysis is CPU-intensive and has its own explicit `mutationTest` task
and required CI job, so normal local `check` remains practical while NFR36 still runs on every CI
change.

The three live-environment tasks validate their inputs before making a request and fail closed
when invoked without a configured target. They are not part of ordinary local `check`:

```bash
LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.staging.example \
LIFEOS_E2E_IDENTITY_BASE_URL=https://identity.staging.example \
LIFEOS_E2E_TASK_GOAL_BASE_URL=https://task-goal.staging.example \
./gradlew endToEndTest

LIFEOS_PERFORMANCE_GATEWAY_BASE_URL=https://gateway.staging.example \
./gradlew performanceTest

LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos-runner.staging.example/run \
LIFEOS_CHAOS_GATEWAY_BASE_URL=https://gateway.staging.example \
LIFEOS_CHAOS_IDENTITY_BASE_URL=https://identity.staging.example \
LIFEOS_CHAOS_TASK_GOAL_BASE_URL=https://task-goal.staging.example \
./gradlew chaosTest
```

## Test suite conventions

Every Java project automatically receives Java 25 toolchain selection, JUnit reporting, Checkstyle,
and Spotless. Every Spring Boot service additionally receives `integrationTest`, `contractTest`,
PIT, and package tasks. The root aggregate tasks discover Java and Spring Boot projects at task
execution time. `settings.gradle.kts` deterministically discovers direct `contracts/*` and
`services/*` directories containing a `build.gradle.kts`, so a conventional new module needs no
hard-coded include or baseline CI task-graph edit. Add a matching
`infrastructure/docker/<service>.Dockerfile` and one non-plain Spring Boot executable jar under
`services/<service>/build/libs/`; the container build and scan scripts discover Dockerfiles instead
of maintaining a separate hard-coded service list. The build script deliberately ignores an
optional `*-plain.jar` produced by a local Gradle `build`.
Both the architecture and container scripts deliberately fail closed as soon as a
`services/*-service` directory or `infrastructure/docker/<service>.Dockerfile` appears without its
required counterpart and packageable executable jar. Treat a service module and its Dockerfile as
one atomic change before expecting the aggregate CI gates to pass.

New services must provide at least one test matching each convention:

- `*IntegrationTest`, `*IntegrationTests`, `*IT`, or `*MigrationTest` for integration coverage.
- `*ContractTest` or `*ContractTests` for executable HTTP, message, or internal-service-boundary
  contracts.

Both tasks fail if no matching class is found. A few legacy services have a small
`supplemental*TestClasses` map in [`build.gradle.kts`](../../build.gradle.kts) for legacy class names
that predate this convention. When a legacy test is renamed to the convention, remove only its map
entry; do not remove the failure-on-empty behavior. A non-Spring-Boot module needs an equivalent
quality convention before it can be added to the release path.

The existing contract suite uses real controller/boundary tests, not generated documentation
checks: gateway forwarding, identity registration and workload validation, and task-goal API
behavior. New public endpoints must extend the relevant `*ContractTest` suite.

`architectureTest` discovers every `services/*-service` directory. Its contract requires a Spring
Boot build, application entry point, service-owned `com.lifeos.<package>` namespace, application
configuration, matching Dockerfile, digest-pinned base image, and a non-root runtime user. Direct
imports of another service's `com.lifeos` package fail the test. The only allowed shared namespace
is one discovered from a `contracts/*/src/main/java/com/lifeos/<namespace>` module, such as the
versioned event contract library; cross-service calls must otherwise stay on explicit HTTP, gRPC,
or event boundaries.

## Formatting and static analysis

Spotless performs deterministic trailing-whitespace and final-newline validation over repository
assets and Java/service configuration. Checkstyle enforces correctness-oriented rules (tabs,
missing final newlines, empty statements, `equals`/`hashCode` mismatch, and simplified boolean
expressions) over production and test sources. This deliberately avoids reformatting an unrelated
large Java baseline while still providing a zero-tolerance format gate for every changed file.

## Security, containers, and SBOMs

Trivy runs from an immutable, versioned container digest and scans the repository plus the built Spring Boot jars
for high/critical, fixable dependency vulnerabilities, secrets, and configuration mistakes. A
separate image scan evaluates every runtime image after it is built. `--ignore-unfixed` prevents a
known issue with no upstream remediation from blocking delivery; it is intentionally outside the
failing result set, so triage it separately and upgrade as soon as a fix exists.
The scanner cache defaults outside the checkout (`$RUNNER_TEMP/lifeos-trivy-cache` in GitHub Actions
or `/tmp/lifeos-trivy-cache` locally) to prevent it from being scanned as repository content. Set
`LIFEOS_TRIVY_CACHE_DIR` only when a different cache location is required.

The runtime Dockerfiles use only the prebuilt executable jar and a non-root UID. Build images with
the SHA tag in CI or set these local overrides:

```bash
LIFEOS_IMAGE_PREFIX=registry.example/lifeos \
LIFEOS_IMAGE_TAG=abc123 \
bash scripts/build-container-images.sh
```

`LIFEOS_PUSH_IMAGES=true` is accepted only after the caller authenticates to the target registry.
The default is local build only, so development commands never publish an image by surprise.

CycloneDX generates the multi-project dependency SBOM at
`build/reports/cyclonedx/bom.{json,xml}`. `verify-sbom.sh` rejects an empty or structurally invalid
SBOM and requires at least one library component with a package URL.

## Staging activation (NFR40–NFR41)

The repository cannot safely invent a deployment target or credentials. Until staging exists, the
always-visible `staging-status` job reports that NFR40 and NFR41 are pending external
infrastructure. It intentionally does not claim that a staging deploy or smoke test passed. The
separate `end-to-end-status`, `performance-status`, and `chaos-status` jobs use the same rule for
their live-environment NFR27 categories.

To activate the real protected-branch staging path, configure the GitHub `staging` environment:

1. Add repository or environment variable `LIFEOS_STAGING_ENABLED=true`.
2. Protect the environment with the required reviewer and branch policy.
3. Grant GitHub Actions permission to publish packages. The deploy job uses its short-lived
   `GITHUB_TOKEN` with `packages: write` to publish immutable images at
   `ghcr.io/<owner>/<repository>/<service>:<full-sha>`.
4. Add environment secret `STAGING_DEPLOY_WEBHOOK_URL`. It must be an HTTPS endpoint that accepts
   a JSON object containing `repository`, `ref`, `sha`, `imagePrefix`, `imageTag`, and `services`;
   it must be idempotent on `(repository, sha)`, pull every referenced immutable image, wait for
   rollout, and return a non-2xx status on failure. Configure that deployment system with a pull
   identity for the GHCR package.
5. Add `STAGING_SERVICE_URLS_JSON` as a repository or environment variable. It must be a JSON
   object keyed by every discovered `infrastructure/docker/<service>.Dockerfile`, for example:

   ```json
   {
     "gateway-service": "https://gateway.staging.example",
     "identity-service": "https://identity.staging.example",
     "task-goal-service": "https://task-goal.staging.example",
     "notification-service": "https://notification.staging.example",
     "profile-service": "https://profile.staging.example"
   }
   ```

   The values must point at each deployed service's management listener through a staging-only,
   authenticated or network-restricted ingress. The default service listener is loopback-bound, so
   do not expose it directly to the public Internet. The smoke job calls only
   `/actuator/health/readiness`, never a business endpoint or secret-bearing request. Adding a
   service Dockerfile without adding its map entry fails the smoke gate closed.
6. To enable the end-to-end, performance, or chaos test, also add the three HTTPS environment
   variables `STAGING_GATEWAY_BASE_URL`, `STAGING_IDENTITY_BASE_URL`, and
   `STAGING_TASK_GOAL_BASE_URL` for their deliberately topology-specific assertions. To enable the
   end-to-end test, add `LIFEOS_E2E_ENABLED=true`. Its invalid registration request is deliberately
   non-mutating and verifies a stable error plus correlation propagation through Gateway and
   Identity.
7. To enable the bounded performance test, add `LIFEOS_PERFORMANCE_ENABLED=true`. Give the GitHub
   runner network access to the staging gateway. The job runs 10 virtual users for 15 seconds;
   adjust the script inputs only alongside a documented benchmark methodology.
8. To enable chaos, add `LIFEOS_CHAOS_ENABLED=true` and the environment secret
   `CHAOS_EXPERIMENT_WEBHOOK_URL`. That HTTPS runner must authenticate the caller, accept the
   documented `dependency-isolation-readiness` JSON payload, inject only a pre-approved staging
   dependency failure, wait for rollback/recovery, and return non-2xx if injection, rollback, or
   verification fails. It must never target production.

On a `dev` or `main` push, `staging-deploy` runs only after every upstream quality job succeeds,
publishes immutable images, and invokes the webhook without printing its URL or response.
`staging-smoke` then retries readiness checks over HTTPS, after which any explicitly enabled
live-test job runs. If activation is enabled but any secret, URL, registry permission, deployment
response, or readiness check is missing/bad, the relevant job fails closed. Pull requests and
feature-branch pushes never receive staging credentials or deploy untrusted code.

To suspend external rollout immediately, set `LIFEOS_STAGING_ENABLED` to anything other than
`true`; the quality stages continue and `staging-status` explicitly reports the deferred state.
