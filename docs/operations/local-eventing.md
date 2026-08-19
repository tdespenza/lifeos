# Local Eventing Reference Stack

The optional `eventing` Compose profile starts one localhost-bound Apache Kafka KRaft broker and
creates the exact development topics used by Calendar, Notification, Document Vault, and the
opt-in Trust Ledger consumer:

- `lifeos.notification.requested.v1` and `.DLT`;
- `lifeos.notification.requested.v2` and `.DLT`;
- `lifeos.notification.delivery-status.v1`.
- `lifeos.notification.requested.v2` is also the privacy-safe Identity recovery-notification
  destination. Identity publishes only generic security-event metadata from its transactional
  outbox; recovery codes, contact data, and secrets are never placed in the event payload, key,
  logs, or metrics. The Identity relay is disabled in tests and can be enabled only when the
  deployment supplies authenticated Kafka connectivity and the provider-side delivery path.
- `lifeos.document.proof.requested.v1` and `.DLT` (Document Vault proof-request producer and the
  opt-in Trust Ledger consumer's durable poison-record destination).

It is developer scaffolding, not a production broker deployment. The single broker is plaintext,
has no ACLs, uses replication factor one, and retains logs for seven days. A production deployment
must provide TLS, workload authentication, topic ACLs, multi-broker replication/min-ISR, retention,
backup, and lag/DLT alerting before any event flow is exposed.

## Start and validate

Copy the ignored local template, set the Postgres values, then start the profile:

```text
cp infrastructure/docker-compose/.env.example infrastructure/docker-compose/.env
# Edit the copied file with local-only values.
docker compose --env-file infrastructure/docker-compose/.env \
  -f infrastructure/docker-compose/docker-compose.yml \
  --profile eventing up -d
```

The broker is intentionally published only on `127.0.0.1:9092`. Host-run Calendar and Notification
use `LIFEOS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`. Check the topology without starting it:

```text
docker compose --env-file infrastructure/docker-compose/.env \
  -f infrastructure/docker-compose/docker-compose.yml \
  --profile eventing config -q
```

The `kafka-topic-init` container exits after idempotently creating the listed topics. Inspect its
logs if a local producer cannot publish. It is not a substitute for a production topic-provisioning
workflow; in particular, applications should not depend on broker auto-creation.

## Existing PostgreSQL volumes

Postgres runs `init-databases.sql` only on a new data volume. If the local volume predates Calendar,
Finance, Profile, Notification, or Document Vault, start Postgres and run the idempotent provisioner
instead of deleting developer data:

```text
bash scripts/provision-local-databases.sh
```

It creates only any missing named LifeOS databases; it does not drop, alter, or overwrite an
existing database. It uses the Postgres credentials already supplied to Compose.

## Calendar reminder path

Calendar writes a generic-only `NotificationRequestedV2` CloudEvent into its durable local outbox,
then the bounded relay publishes it to `lifeos.notification.requested.v2` with recipient UUID as
the key. Notification consumes V2 independently from V1, applies its durable inbox dedupe, and
never puts event titles, locations, endpoint addresses, tokens, or rendered private content in the
Kafka record key, topic, metric labels, or logs. See [Calendar API](../api/calendar-service.md) and
[Notification API](../api/notification-service.md) for the exact contract.

Document Vault writes a `DocumentProofRequestedV1` CloudEvent into its durable proof outbox and,
when `DOCUMENT_VAULT_PROOF_OUTBOX_RELAY_ENABLED=true`, publishes it to
`lifeos.document.proof.requested.v1` with `document/{documentId}` as the bounded record key. The
relay uses leased claims, capped exponential backoff, and a durable dead-letter table after the
configured attempt limit. The payload contains only owner/tenant scope, document UUID/version, and
checksum. When `TRUST_LEDGER_KAFKA_ENABLED=true`, Trust Ledger consumes that topic with a bounded
retry policy, sends malformed or exhausted records to `.DLT`, and durably projects only the request
metadata as `PENDING_EXTERNAL_ANCHOR`; it stores no document bytes and does not claim an external
anchor. The Kafka consumer and topic ACLs remain opt-in deployment work.
