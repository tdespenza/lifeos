# Epic 2 gateway — Stories 2.1 and 2.2

The gateway owns a finite deployment configuration of versioned public path prefixes. A request
cannot select an arbitrary upstream, and a downstream response cannot replace the request's
validated correlation ID.

```mermaid
flowchart LR
    A[Client request] --> B[Validate one X-Correlation-ID or generate UUID]
    B --> C[Bind MDC + ScopedValue]
    C --> D{Configured path-segment prefix?}
    D -- no --> E[Controlled 404 ROUTE_NOT_FOUND]
    D -- yes --> F{Protected route?}
    F -- yes --> G[Validate bearer with identity-service]
    G -- invalid --> H[401; redacted security metric]
    G -- unavailable --> I[503 fail closed; redacted security metric]
    G -- valid --> J[Forward sanitized subject context]
    F -- no --> J[Copy safe request contract]
    J --> K[Forward to fixed upstream with same correlation ID]
    K --> L[Copy status/body/public headers]
    L --> M[Return response with same correlation ID]
```

Current routes:

```text
/api/v1/accounts -> identity-service
/api/v1/auth     -> identity-service
/api/v1/auth/sessions -> identity-service (protected)
/api/v1/goals    -> task-goal-service (protected)
```

The route table is materialized once at startup and resolved by path-segment prefixes. Resolution is
bounded by the request path length and does not perform I/O. Request and response bodies are buffered
only under configured byte limits; connection and read deadlines turn transport failures into generic
502/504 responses. Protected routes call identity-service's workload-authenticated validation
adapter before request-body forwarding. The gateway forwards only bounded subject facts and strips
caller-supplied subject/workload headers. Identity-owned account/auth prefixes remain explicitly
public at the gateway where bootstrap operations require it; account lookup and session management
are protected by more-specific gateway policies. Identity-service still enforces its operation-level rules. Rate limiting, circuit breaking, and
bulkheads remain Story 2.3 responsibilities.
