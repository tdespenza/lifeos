# identity-service API

Base URL (local): `http://localhost:8081`

Management URL (local): `http://localhost:9081`

Status: registration only. No authentication (OAuth2/OIDC/JWT/passkeys) is implemented yet — no ADR covers auth design yet; see the identity-service section of `REQUIREMENTS.md` for the target scope. `UserAccount` deliberately does not store credentials, to avoid half-implementing security-sensitive password handling ahead of a real auth design.

All requests receive a server-generated `X-Correlation-ID` response header. Any incoming value is ignored so caller-controlled personal data cannot enter MDC, request context, or structured logs. Registration logs include the generated correlation context and event outcome without logging the email address, account identifier, or database exception details.

## `POST /api/v1/accounts`

Register a new account.

### Request Body

```json
{
  "email": "ada@example.com",
  "displayName": "Ada Lovelace"
}
```

`email` must be non-blank and a valid email address; `displayName` must be non-blank (enforced via Jakarta Bean Validation on [`RegisterAccountRequest`](../../services/identity-service/src/main/java/com/lifeos/identity/account/dto/RegisterAccountRequest.java)).

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Account created | `AccountResponse` (see below), `Location` header set to `/api/v1/accounts/{id}` |
| `400 Bad Request` | Validation failure (blank/invalid email or displayName) | Generic RFC 9457 problem detail; field values are not echoed |
| `409 Conflict` | An account already exists for that email, including a concurrent uniqueness conflict | Generic plain-text message; does not echo the email or database details |

### Example Response (201)

```json
{
  "id": "d49f7cc3-78d4-4d68-8abd-b76fb3d8a77d",
  "email": "ada@example.com",
  "displayName": "Ada Lovelace",
  "createdAt": "2026-07-31T04:10:02.468067Z"
}
```

## `GET /api/v1/accounts/{id}`

Fetch an account by id.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Found | `AccountResponse` |
| `404 Not Found` | No account with that id | Plain-text message |

## Operational endpoints

Spring Boot Actuator endpoints exposed by the service:

| Endpoint | Purpose |
| --- | --- |
| `GET http://localhost:9081/actuator/health` | Overall service health; database details are not included in the response |
| `GET http://localhost:9081/actuator/health/liveness` | Process liveness probe |
| `GET http://localhost:9081/actuator/health/readiness` | Readiness probe, including database availability |
| `GET http://localhost:9081/actuator/prometheus` | Prometheus-compatible request and application metrics |

Actuator runs on a separate management listener bound to loopback by default. Set `IDENTITY_MANAGEMENT_PORT` and `IDENTITY_MANAGEMENT_ADDRESS` for a private deployment interface, and keep that listener behind the deployment network boundary. Only the endpoints above plus `info` are exposed (see [`application.yml`](../../services/identity-service/src/main/resources/application.yml)) — no `/actuator/env`, `/actuator/beans`, etc. are exposed, to avoid leaking configuration details. HTTP observations are traced through the configured Micrometer/OpenTelemetry bridge and exported to the OTLP endpoint configured by `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.

## Data store

PostgreSQL, database `lifeos_identity`, table `user_account` (id, email — unique constraint `uk_user_account_email`, displayName, createdAt). See [`UserAccount`](../../services/identity-service/src/main/java/com/lifeos/identity/account/UserAccount.java). Schema is currently managed by Hibernate's `ddl-auto: update` — this is a known Phase 1 shortcut; a real migration tool (e.g. Flyway) should replace it before this goes further, per the persistence-model tradeoff called out in [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md).

For deployed environments, set `IDENTITY_DATASOURCE_URL`, `IDENTITY_DATASOURCE_USERNAME`, and `IDENTITY_DATASOURCE_PASSWORD` rather than relying on the local-development defaults in `application.yml`.
