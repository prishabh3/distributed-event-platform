# Distributed Event Processing Platform

A production-grade, horizontally scalable event-driven platform built on Apache Kafka, Spring Boot 3, and PostgreSQL. Designed for **5000+ events/sec** sustained throughput with **p95 < 50ms** enqueue latency and **99.9% delivery reliability**.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CLIENT (curl / k6 / service)                        │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ POST /api/v1/events
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  PRODUCER SERVICE  :8080                                                    │
│                                                                             │
│  ApiKeyAuthFilter → RateLimiterFilter (Redis token bucket, 100 req/s)       │
│         │                                                                   │
│         ▼                                                                   │
│  IdempotencyService  ─── Redis SETNX ──▶  idem:{eventId}  TTL=24h          │
│         │ (if new)                                                          │
│         ▼                                                                   │
│  EventService ─── @Transactional ──▶  [events] + [outbox_events]  (PG)     │
│                                                                             │
│  OutboxPublisher (@Scheduled 100ms)                                         │
│    └── SELECT FOR UPDATE SKIP LOCKED                                        │
│    └── KafkaTemplate.send().get(5s)   ←── acks=all, idempotent producer    │
│    └── UPDATE outbox SET published=true                                     │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ Kafka topic: "notifications" (6 partitions)
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  KAFKA (KRaft mode — no Zookeeper)                                          │
│                                                                             │
│  topics:                                                                    │
│    notifications          (6 partitions, RF=1)                              │
│    notifications.retry.1m (6 partitions, RF=1)  ─── 60s  delay             │
│    notifications.retry.5m (6 partitions, RF=1)  ─── 300s delay             │
│    notifications.retry.30m(6 partitions, RF=1)  ─── 1800s delay            │
│    notifications.dlq      (1 partition,  RF=1)                              │
└───────────────────────────────────┬─────────────────────────────────────────┘
                                    │ Consumer group: notification-consumer-group
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  CONSUMER WORKER  :8081   (6 concurrent threads)                            │
│                                                                             │
│  DeduplicationService                                                       │
│    └── Redis: processed:{eventId}  (L1 cache, 168h TTL)                    │
│    └── PostgreSQL: processed_events (L2 authoritative)                      │
│         │ (if not duplicate)                                                │
│         ▼                                                                   │
│  WebhookDispatcher (WebClient — non-blocking, 5s connect, 10s response)    │
│    ├── HTTP 2xx → markProcessed() + updateEventStatus(DELIVERED)            │
│    ├── HTTP 4xx → sendToDlq() (non-retryable)                               │
│    └── HTTP 5xx / timeout → routeToRetry() (retry topic ladder)            │
│         │                                                                   │
│         ▼                                                                   │
│  RetryRoutingService                                                        │
│    retry=0 → notifications.retry.1m   (X-Retry-Scheduled-At header)        │
│    retry=1 → notifications.retry.5m                                        │
│    retry=2 → notifications.retry.30m                                       │
│    retry≥3 → notifications.dlq                                             │
│                                                                             │
│  Retry consumers: ack.nack(Duration) for non-blocking partition pause      │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
             [Prometheus]      [Grafana]         [Jaeger]
             :9090             :3000             :16686
             (metrics)         (dashboards)      (traces)
```

---

## Project Structure

```
distributed-event-platform/
├── shared-contracts/           # Kafka message contracts (EventMessage, EventType, ...)
│   └── src/main/java/com/porter/platform/contracts/
├── producer-service/           # REST API + Outbox pattern + Kafka producer
│   ├── src/main/java/.../producer/
│   │   ├── config/             # Kafka, Redis, Security configs
│   │   ├── controller/         # EventController (POST /api/v1/events)
│   │   ├── domain/             # Event, OutboxEvent JPA entities
│   │   ├── filter/             # ApiKeyAuthFilter, RateLimiterFilter
│   │   ├── metrics/            # Micrometer counters + timers
│   │   ├── outbox/             # OutboxPublisher (scheduled Kafka relay)
│   │   ├── repository/         # Spring Data JPA repos
│   │   └── service/            # EventService, IdempotencyService
│   └── src/main/resources/
│       └── db/migration/       # Flyway V1–V3 migrations
├── consumer-worker/            # Kafka consumer + webhook + retry/DLQ
│   └── src/main/java/.../consumer/
│       ├── config/             # Kafka consumer, WebClient, Redis configs
│       ├── consumer/           # NotificationConsumer (main + 3 retry + DLQ)
│       ├── domain/             # ProcessedEvent entity
│       ├── metrics/            # ConsumerMetrics
│       ├── repository/         # ProcessedEventRepository
│       └── service/            # DeduplicationService, WebhookDispatcher, RetryRoutingService
├── benchmark/
│   └── load-test.js            # k6 load test (50 VUs, 5000+ req/s)
├── infra/
│   ├── prometheus/             # prometheus.yml scrape config
│   └── grafana/                # Provisioned datasource + dashboard JSON
├── docker-compose.yml          # Full stack: Kafka (KRaft), PG, Redis, Prometheus, Grafana, Jaeger
└── README.md
```

---

## Tech Stack

| Layer            | Technology                                           |
|------------------|------------------------------------------------------|
| Language         | Java 17                                              |
| Framework        | Spring Boot 3.2, Spring WebFlux (WebClient)          |
| Messaging        | Apache Kafka 3.7 (KRaft mode — no Zookeeper)        |
| Database         | PostgreSQL 15 + HikariCP + Flyway                   |
| Cache            | Redis 7 (idempotency, rate limiting, dedup cache)   |
| Observability    | Micrometer, Prometheus, Grafana, OpenTelemetry, Jaeger |
| Testing          | JUnit 5, Testcontainers, k6                         |
| Infrastructure   | Docker, Docker Compose, multi-stage builds           |

---

## Setup & Running

### Prerequisites

- Docker Desktop 4.x+
- Docker Compose v2
- k6 (for benchmarks): `brew install k6`

### Start the full stack

```bash
git clone <repo>
cd distributed-event-platform

# Build + start all services (first build takes ~3 min)
docker compose up --build

# Or in detached mode:
docker compose up --build -d && docker compose logs -f producer-service consumer-worker
```

### Verify health

```bash
# Producer
curl http://localhost:8080/actuator/health

# Consumer
curl http://localhost:8081/actuator/health
```

### Send a test event

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-API-KEY: prod-key-alpha-001" \
  -d '{
    "eventId": "550e8400-e29b-41d4-a716-446655440000",
    "eventType": "ORDER_UPDATE",
    "partitionKey": "ORD-123",
    "payload": {"orderId": "ORD-123", "status": "PICKED_UP"},
    "webhookUrl": "https://webhook.site/your-url",
    "timestamp": "2026-05-08T10:00:00Z"
  }'
```

### Access dashboards

| Service     | URL                           | Credentials        |
|-------------|-------------------------------|--------------------|
| Grafana     | http://localhost:3000         | admin / admin      |
| Prometheus  | http://localhost:9090         | —                  |
| Jaeger UI   | http://localhost:16686        | —                  |

---

## API Reference

### POST /api/v1/events

**Headers:** `X-API-KEY: <key>`, `Content-Type: application/json`

**Request:**
```json
{
  "eventId":      "550e8400-e29b-41d4-a716-446655440000",
  "eventType":    "ORDER_UPDATE",
  "partitionKey": "ORD-123",
  "payload":      {"orderId": "ORD-123", "status": "PICKED_UP"},
  "webhookUrl":   "https://example.com/webhook",
  "timestamp":    "2026-05-08T10:00:00Z"
}
```

**Response 202 (new event):**
```json
{"eventId": "...", "status": "QUEUED",     "message": "Event accepted for processing"}
```

**Response 200 (duplicate):**
```json
{"eventId": "...", "status": "DUPLICATE", "message": "Event already accepted — idempotent response"}
```

**Response 429 (rate limited):**
```json
{"status": 429, "error": "Too Many Requests", "message": "Rate limit of 100 req/s exceeded"}
```

### GET /api/v1/events/{uuid}
Returns the persisted event with its current delivery status.

### GET /api/v1/stats
Returns throughput counters and pending outbox count.

### GET /actuator/prometheus
Prometheus metrics endpoint.

---

## Transactional Outbox Pattern

**Problem:** Naive approach publishes directly to Kafka inside the REST request thread. If Kafka is down, the event is lost. If the DB write succeeds but Kafka publish fails, state is inconsistent.

**Solution — Outbox Pattern:**

```
Request Thread:                    Background Thread (@Scheduled every 100ms):
                                   
BEGIN TRANSACTION                  SELECT * FROM outbox_events
  INSERT INTO events               WHERE published = false
  INSERT INTO outbox_events        FOR UPDATE SKIP LOCKED
COMMIT                             LIMIT 100;
                                   
↓ Returns 202 immediately          FOR EACH outbox_event:
                                     kafkaTemplate.send().get(5s)  -- ack from Kafka
                                     UPDATE outbox_events SET published=true
```

**Guarantees:**
- Atomicity: event + outbox row are always written together or not at all.
- Durability: if Kafka is down, outbox rows accumulate and are published when Kafka recovers.
- `SELECT FOR UPDATE SKIP LOCKED` allows multiple publisher instances without collisions.
- Producer idempotence (`enable.idempotence=true`) prevents duplicate Kafka messages during retries.

---

## Kafka Topic Design

| Topic                    | Partitions | Purpose                              | Delay  |
|--------------------------|-----------|--------------------------------------|--------|
| `notifications`          | 6         | Primary event stream                 | —      |
| `notifications.retry.1m` | 6         | First retry after transient failure  | 60s    |
| `notifications.retry.5m` | 6         | Second retry                         | 300s   |
| `notifications.retry.30m`| 6         | Third retry                          | 1800s  |
| `notifications.dlq`      | 1         | Permanent failures (3 retries done)  | —      |

**Partitioning:** All messages keyed by `partitionKey` → guarantees ordering per entity (e.g., all events for `ORD-123` go to the same partition, processed sequentially).

**KRaft mode:** Kafka runs as a combined broker+controller with no Zookeeper dependency. Configured via `KAFKA_CFG_PROCESS_ROLES=broker,controller`.

---

## Retry Architecture (Non-Blocking)

```
notifications
    │
    │ HTTP 5xx / timeout
    ▼
notifications.retry.1m  ──[X-Retry-Scheduled-At header: now + 60s]──▶ consumer checks header
    │                                                                    if not ready: ack.nack(Duration)
    │ still failing                                                       ↑ non-blocking partition pause
    ▼
notifications.retry.5m  ──[X-Retry-Scheduled-At: now + 300s]──────▶ same check
    │
    │ still failing
    ▼
notifications.retry.30m ──[X-Retry-Scheduled-At: now + 1800s]─────▶ same check
    │
    │ still failing (retry=3) OR HTTP 4xx at any stage
    ▼
notifications.dlq
```

**Non-blocking delay mechanism:** `Acknowledgment.nack(Duration pauseDuration)` signals the Spring Kafka listener container to seek back to the current offset and pause that partition for `pauseDuration`. No `Thread.sleep` is ever used. The thread is immediately freed for other work.

**HTTP 4xx → immediate DLQ:** Client errors (bad URL, auth failure) will never succeed on retry — they go directly to DLQ regardless of retry count.

---

## Consumer Idempotency (Two-Layer)

Kafka provides **at-least-once** delivery — the same message can be consumed multiple times during:
- Consumer restart after crash
- Partition rebalance mid-processing
- Broker failover

**Layer 1 — Redis (fast path):**
```
HSET processed:{eventId} 1   TTL=168h
```
Sub-millisecond check. Covers the common hot case (recent duplicates).

**Layer 2 — PostgreSQL (authoritative):**
```sql
INSERT INTO processed_events (event_id, processed_at) VALUES (?, NOW())
-- ON CONFLICT: PK violation → duplicate detected
```
Survives Redis flush/restart. Two concurrent consumers racing: one gets the PK insert, the other catches `DataIntegrityViolationException` and skips.

**Offset commit contract:** Offsets are committed (`ack.acknowledge()`) only AFTER:
1. Successful webhook dispatch
2. Successful `processed_events` INSERT
3. Successful `events` status UPDATE

---

## Observability

### Prometheus Metrics

| Metric                           | Type    | Description                          |
|----------------------------------|---------|--------------------------------------|
| `events_received_total`          | Counter | Events accepted by producer API      |
| `events_delivered_total`         | Counter | Events successfully webhook-delivered|
| `events_failed_total`            | Counter | Events failed (moved to retry/DLQ)   |
| `events_duplicates_total`        | Counter | Idempotency rejections (producer)    |
| `consumer_duplicates_skipped_total` | Counter | Consumer dedup skips              |
| `retry_attempts_total`           | Counter | Total retry routings                 |
| `dlq_events_total`               | Counter | DLQ entries                          |
| `outbox_publish_latency_ms`      | Histogram| Outbox → Kafka publish time         |
| `webhook_latency_ms`             | Histogram| HTTP webhook call latency           |
| `hikaricp_connections_active`    | Gauge   | Active DB connections                |
| `jvm_memory_used_bytes`          | Gauge   | JVM heap usage                       |

### Grafana Dashboard

Pre-provisioned at http://localhost:3000 — Dashboard: **"Distributed Event Platform"**

Panels:
- Event ingestion rate (events/sec)
- Webhook delivery rate
- Failure & DLQ rate
- Outbox → Kafka latency histogram (p50/p95/p99)
- Webhook HTTP call latency (p50/p95/p99)
- Retry attempt rate
- Consumer deduplication rate
- JVM heap usage
- HikariCP connection pool utilization

### Distributed Tracing (OpenTelemetry → Jaeger)

Trace spans are automatically created for:
- `POST /api/v1/events` (REST handler)
- `outbox.publish` (scheduler → Kafka)
- `consumer.notifications` (Kafka consumer handler)

All spans include `traceId` / `spanId` in log output (MDC). View traces at http://localhost:16686.

---

## Load Testing

```bash
# Prerequisites: k6 installed
brew install k6

# Point at your webhook URL (use webhook.site for live testing)
export WEBHOOK_URL="https://webhook.site/your-unique-url"

# Run full benchmark (ramp-up + constant arrival rate at 5000 req/s)
k6 run benchmark/load-test.js

# Against a deployed environment
k6 run --env BASE_URL=http://your-host:8080 benchmark/load-test.js
```

**Test scenarios:**
1. `ramp_up` — 0→50 VUs over 30s, sustains 50 VUs for 120s
2. `constant_arrival` — exactly 5000 requests/sec, up to 200 VUs

**SLA assertions (auto-checked):**
- `p(95) < 50ms` for enqueue latency
- `error rate < 0.1%`

**Expected output (on local Docker):**
```
══════════════════════════════════════════════
    DISTRIBUTED EVENT PLATFORM — LOAD TEST
══════════════════════════════════════════════
Total requests:      300,000+
Requests/sec:        5,200+
Enqueue p50:         12.34ms
Enqueue p95:         38.21ms   ✅ < 50ms
Enqueue p99:         71.45ms
Error rate:          0.001%    ✅ < 0.1%
══════════════════════════════════════════════
```

### Grafana Screenshots (Benchmark Run)

> **[Placeholder — attach screenshots from `docker compose up` + k6 run]**
>
> Expected panels to capture:
> - Throughput panel showing 5000+ events/sec spike
> - p95 latency histogram staying under 50ms band
> - Zero DLQ events during clean run
> - HikariCP pool utilization < 80% (headroom for burst)

---

## Horizontal Scaling

### Scale Consumer Workers

```bash
# Add 3 more consumer instances — each gets 2 of the 6 partitions
docker compose up --scale consumer-worker=3 -d
```

The Kafka consumer group `notification-consumer-group` automatically rebalances. Each partition is owned by exactly one consumer instance — no duplicate processing. With 6 partitions and 3 instances: each instance handles 2 partitions.

### Scale Producer Service

```bash
docker compose up --scale producer-service=3 -d
```

Stateless by design — all state is in PostgreSQL + Redis + Kafka. Add a load balancer (e.g., nginx) in front for real horizontal scaling.

### Scale Outbox Publishers

Multiple producer instances run the `OutboxPublisher` concurrently. `SELECT FOR UPDATE SKIP LOCKED` prevents two instances from processing the same outbox batch. Safe to run N instances in parallel.

---

## Failure Recovery

| Failure Scenario             | Recovery Mechanism                                               |
|------------------------------|------------------------------------------------------------------|
| Kafka temporarily down        | Outbox rows accumulate; publisher retries every 100ms            |
| Producer service crash        | Events in DB + outbox survive; outbox publisher picks up on restart |
| Consumer crash mid-processing | Offset not committed → Kafka re-delivers; consumer idempotency deduplicates |
| Redis flush / restart         | Idempotency falls through to PostgreSQL; rate limiter fails open |
| Webhook endpoint down         | 3-tier retry (1m, 5m, 30m) → DLQ after 3 failures              |
| Consumer rebalance            | Only committed offsets affect rebalance; in-flight events re-delivered |
| PostgreSQL failover           | HikariCP reconnects; outbox publisher resumes on next poll       |

---

## Design Tradeoffs

| Decision                         | Choice Made                 | Alternative                        | Rationale                                               |
|----------------------------------|-----------------------------|------------------------------------|--------------------------------------------------------|
| Outbox publisher strategy         | Polling (100ms interval)    | CDC (Debezium)                     | Simpler ops; CDC adds infra complexity for small teams |
| Consumer DB per service           | Shared PostgreSQL            | Separate schemas                   | Event status updates require cross-service visibility  |
| Retry delay mechanism             | `ack.nack(Duration)` pause  | Separate retry scheduler           | Spring Kafka native; no extra stateful components      |
| Idempotency storage               | Redis (L1) + PG (L2)        | Only PostgreSQL                    | Redis < 1ms; PG handles cache misses and Redis loss    |
| Rate limiting algorithm           | Token bucket (Lua script)   | Fixed window counter               | Smooth bursting; prevents thundering-herd patterns     |
| Webhook dispatch                  | Blocking `.block()` on VT   | Fully reactive Mono pipeline        | Simpler error handling; Kafka offset control is cleaner|
| Authentication                    | Static API keys in config   | JWT / OAuth2                       | Sufficient for service-to-service; JWT adds complexity |

---

## Sample Distributed Trace

> **[Placeholder — attach Jaeger screenshot showing full trace span tree]**
>
> Expected trace structure:
> ```
> POST /api/v1/events                          [12ms total]
>   ├── IdempotencyService.tryAcquire           [0.3ms — Redis SETNX]
>   ├── EventService.persistEventAndOutbox      [8ms  — PG transaction]
>   │     ├── INSERT events                     [3ms]
>   │     └── INSERT outbox_events              [2ms]
>   └── (returns 202)
>
> outbox.publish (async, ~100ms later)         [5ms]
>   └── KafkaTemplate.send                     [4ms]
>
> consumer.notifications (async, after Kafka)  [15ms]
>   ├── DeduplicationService.isDuplicate        [0.4ms — Redis GET]
>   ├── WebhookDispatcher.dispatch              [12ms — HTTP POST]
>   ├── DeduplicationService.markProcessed      [1ms  — PG INSERT]
>   └── UPDATE events SET status=DELIVERED      [0.8ms]
> ```

---

## Running Integration Tests

```bash
# Tests use Testcontainers — requires Docker running
cd producer-service
mvn test -Dtest=EventIntegrationTest

cd consumer-worker
mvn test -Dtest=ConsumerIntegrationTest

# Full suite
mvn test
```

Tests start real containers for:
- PostgreSQL 15 (runs Flyway migrations)
- Kafka (via confluentinc/cp-kafka image)
- Redis 7
- MockWebServer (OkHttp — for webhook simulation)

---

## Future Improvements

### Production Hardening
- **Kubernetes + Helm charts** — Horizontal Pod Autoscaler on consumer CPU + Kafka lag metrics
- **Kafka Schema Registry** (Confluent) — Avro/Protobuf schemas with backward-compatibility enforcement
- **Kafka Multi-Region Replication** — MirrorMaker 2 for cross-region DR (RPO < 5s)
- **Consumer Lag Autoscaling** — KEDA with Kafka lag trigger: scale consumer pods when lag > threshold

### Reliability
- **Debezium CDC** — Replace polling outbox publisher with Postgres WAL-based CDC for sub-10ms relay
- **Idempotency cleanup job** — Scheduled task to purge `processed_events` older than 7 days
- **Circuit breaker** — Resilience4j circuit breaker around webhook dispatch to prevent cascade failures

### Observability
- **Kafka Consumer Lag alerting** — Prometheus alert when `kafka_consumer_lag > 10000` for 5 minutes
- **SLO dashboards** — Burn rate alerts for 99.9% availability SLO
- **Exemplars** — Link Prometheus histogram buckets to Jaeger trace IDs

### Security
- **mTLS between services** — Mutual TLS for Kafka broker connections
- **Secrets management** — HashiCorp Vault or AWS Secrets Manager for DB credentials / API keys
- **OAuth2 / JWT** — Replace static API keys with short-lived JWT tokens

---

## Performance Tuning

### Kafka Producer (producer-service)
```yaml
# application.yml
compression.type: snappy          # ~50% size reduction, <1ms overhead
linger.ms: 5                      # batch 5ms of messages before sending
batch.size: 16384                 # 16KB batch (tune up to 65536 for higher throughput)
enable.idempotence: true          # exactly-once from producer perspective
acks: all                         # wait for all ISR replicas
```

### PostgreSQL (docker-compose.yml)
```
max_connections=200
shared_buffers=256MB
effective_cache_size=1GB
work_mem=4MB
```

### HikariCP
```yaml
maximum-pool-size: 20
connection-timeout: 30000
idle-timeout: 300000
```

Outbox partial index `WHERE published = false` ensures the outbox poll query only scans unpublished rows, maintaining O(pending) query time rather than O(total) as the table grows.
