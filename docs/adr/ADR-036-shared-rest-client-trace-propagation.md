# ADR-036: Shared W3C propagation for outbound RestClient calls

## Status

Accepted — implemented for every Spring Boot service discovered by the root build.

## Context

LifeOS services use bounded `RestClient` adapters for Identity and other internal dependencies.
Some adapters replace the request factory or clone the Spring-managed builder, which makes relying
only on implicit observation customization fragile: a request can carry a correlation ID while
losing the active W3C `traceparent`. That breaks end-to-end traces at authorization and
cross-service boundaries.

## Decision

The `contracts:observability` module publishes a small Spring Boot auto-configuration. It registers
a `RestClientCustomizer` that injects the active OpenTelemetry context with the standard W3C
propagator on every Spring-managed outbound `RestClient`. The root Boot-service convention adds the
module automatically, so newly discovered services receive the same boundary without a per-service
build edit. Existing gateway-specific client isolation and correlation headers remain unchanged;
the interceptor only sets standard `traceparent`/`tracestate` headers from the current span.

The interceptor is intentionally limited to outbound HTTP. Kafka and other asynchronous transports
must continue to inject W3C context in their message headers at the producer/consumer boundary.

## Consequences

Every service has one consistent REST propagation behavior, including clients with custom bounded
timeouts. The contract module adds a small dependency and one interceptor invocation per request;
there is no retry, buffering, credential, or payload coupling. A focused contract test exercises a
real `RestClient` request and verifies a valid W3C `traceparent` header.

## Verification

```text
./gradlew :contracts:observability:test
./gradlew check
```
