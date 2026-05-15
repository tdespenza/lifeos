# AGENT.md - Universal FAANG-Grade Engineering Agent Rules

## Purpose

This file defines how AI coding agents, automation agents, and human contributors must work in this repository. It is language-agnostic and framework-agnostic. It applies to backend systems, frontends, mobile apps, desktop apps, CLIs, APIs, distributed systems, data platforms, AI systems, blockchain systems, infrastructure, libraries, and SDKs.

Every change must move the application toward production-grade engineering quality: correctness, speed, efficient algorithms, scalability, reliability, security, observability, maintainability, and testability.

## Source of Truth

All development must stay aligned with [REQUIREMENTS.md](REQUIREMENTS.md). Before implementing, refactoring, reviewing, or documenting a feature, verify that the work supports the product vision, architecture, roadmap, technology choices, and engineering standards described there.

If a requested change conflicts with [REQUIREMENTS.md](REQUIREMENTS.md), call out the conflict, explain the tradeoff, and recommend the safest path forward.

## Non-Negotiable Engineering Standard

Do not merely write code. For every meaningful change, reason about:

- Correctness and edge cases
- Algorithmic efficiency and data structure choice
- Runtime complexity and memory complexity
- I/O, network, database, and serialization behavior
- Concurrency, cancellation, backpressure, and race safety
- Failure modes, recovery behavior, and reliability
- Security, privacy, secrets, and abuse cases
- Observability through logs, metrics, traces, and health checks
- Maintainability, module boundaries, naming, and testability
- Documentation, operational readiness, and production support

If a change touches a hot path, large dataset, external integration, security boundary, distributed workflow, user-facing latency, or persistence model, explicitly document the tradeoffs.

## Required Workflow

### 1. Understand the Problem

Before changing files, identify:

- Goal
- Users or consumers
- Inputs and outputs
- Functional requirements
- Non-functional requirements
- Constraints from [REQUIREMENTS.md](REQUIREMENTS.md)
- Assumptions
- Failure scenarios

### 2. Inspect Existing Architecture

Before implementing, inspect the current structure and patterns:

- Module boundaries
- Language and framework conventions
- Dependency patterns
- Configuration strategy
- Error handling strategy
- Logging, metrics, tracing, and health checks
- Testing patterns
- CI/CD and deployment expectations

Prefer existing patterns over new abstractions unless the existing approach is insufficient.

### 3. Choose the Simplest Production-Grade Design

Prefer clear contracts, cohesive modules, explicit dependencies, bounded resources, immutable data where practical, safe concurrency, and readable code.

Avoid over-engineering, hidden side effects, global mutable state, circular dependencies, unbounded queues or caches, premature abstractions, and clever code that is difficult to maintain.

### 4. Implement With Verification

Every meaningful change must include tests or a clear explanation for why tests are not applicable. Run the relevant build, lint, static analysis, test, benchmark, or validation command when available.

## FAANG Engineering Principles

### Correctness

- Handle valid inputs correctly.
- Reject invalid inputs safely.
- Preserve invariants and valid state transitions.
- Define failure and recovery behavior.
- Cover edge cases with tests.

### Algorithms and Data Structures

- Choose data structures based on access patterns.
- Prefer O(1), O(log n), O(n), or O(n log n) solutions when possible.
- Avoid O(n^2) or worse behavior unless inputs are bounded and documented.
- Use hash maps/sets for fast lookup, heaps for priority scheduling, queues for buffering, tries for prefix search, graphs for relationships, dynamic programming for overlapping subproblems, and streaming algorithms for large or continuous data.
- Document input size, time complexity, space complexity, worst-case behavior, and alternatives for non-trivial algorithms.

### Performance

- Keep hot paths fast and measurable.
- Avoid N+1 queries, unnecessary full scans, repeated remote calls, excessive allocation, blocking event loops, large payloads, and avoidable serialization overhead.
- Use caching carefully with explicit invalidation and bounded memory.
- Benchmark or profile performance-sensitive changes.

### Scalability

- Design APIs and data access with pagination, batching, streaming, filtering, and sorting where needed.
- Keep resources bounded.
- Consider horizontal scaling, state management, partitioning, queue depth, rate limiting, and backpressure.
- Isolate failure domains.

### Reliability

- Assume dependencies fail.
- Use timeouts, bounded retries, exponential backoff with jitter, idempotency, circuit breakers, bulkheads, and dead-letter handling where appropriate.
- Support graceful startup, shutdown, health checks, and partial failure behavior.

### Security

- Enforce authentication and authorization at the correct boundary.
- Validate inputs and encode outputs.
- Protect secrets and sensitive data.
- Never log tokens, passwords, private keys, session cookies, connection strings, personal data, payment data, or internal security details.
- Use standard cryptographic libraries; never invent cryptography.
- Consider abuse cases, dependency risks, audit logging, and rate limits.

### Observability

- Add structured logs, metrics, traces, correlation IDs, health checks, dashboards, and alerts for critical paths.
- Measure latency, throughput, errors, saturation, queue depth, dependency health, cache hit rate, and business-critical events.
- Avoid noisy alerts and high-cardinality metrics.

### Maintainability

- Use clear names and cohesive modules.
- Keep dependencies explicit.
- Minimize complexity and duplication.
- Explain why in comments only when the reasoning is not obvious.
- Update documentation for important decisions and operational behavior.

### Testability

- Prefer deterministic, isolated, repeatable tests.
- Use unit tests for pure logic, integration tests for boundaries, contract tests for APIs, end-to-end tests for critical flows, property-based tests for algorithms, load tests for hot paths, and security tests for trust boundaries.

## Required Review Template

For implementation, refactoring, debugging, architecture, or review work, respond with concise sections when relevant:

```markdown
## Problem Understanding

## Existing Architecture Review

## Constraints and Assumptions

## Recommended Design

## Algorithm and Data Structure Choice

## Complexity Analysis

## Performance Considerations

## Scalability Considerations

## Reliability Considerations

## Security Considerations

## Observability Considerations

## Implementation Plan

## Testing Strategy

## Risks and Tradeoffs

## Files or Modules Affected

## Definition of Done
```

For small tasks, keep these sections brief. For complex changes, provide deeper reasoning.

## Required Quality Checklist

Before calling work complete, verify:

- The change aligns with [REQUIREMENTS.md](REQUIREMENTS.md).
- The problem is solved at the root cause.
- Valid, invalid, edge, and failure cases are handled.
- Data structures and algorithms are efficient for expected scale.
- Time and space complexity are acceptable and documented for non-trivial logic.
- No avoidable N+1 queries, full scans, unbounded memory growth, or unnecessary I/O are introduced.
- Concurrency is bounded, cancellable, and race-safe.
- Security boundaries are preserved and secrets are not exposed.
- Observability is added or updated where production diagnosis requires it.
- Tests and documentation are updated as needed.
- The work is ready for production review.

## Architecture Decision Records

Create or update an ADR under `docs/adr/` when a decision affects architecture, data models, scaling strategy, security model, deployment model, external dependencies, API contracts, messaging, persistence, AI providers, or blockchain assumptions.

## Final Standard

The application should be strong enough to discuss in senior engineering interviews, FAANG-style system design reviews, enterprise architecture reviews, investor technical due diligence, and production readiness reviews.
