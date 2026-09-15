# CareOS operational foundation

This document is the runbook for the repository-local operational baseline. It covers the signals that the current foundation actually emits. It is not a production hosting, disaster-recovery, or clinical-incident plan.

## Runtime signal contract

| Signal | Access | Meaning | Orchestrator action |
| ------ | ------ | ------- | ------------------- |
| `GET /livez` | Public, status only | Spring application liveness. It deliberately excludes PostgreSQL, Redis, SMTP, object storage, ClamAV, and optional platform adapters. | Restart only after repeated liveness failure or an unreachable process. |
| `GET /readyz` | Public, status only | Traffic readiness: application readiness plus PostgreSQL and Redis. Browser identity cannot function safely without either dependency. | Remove the instance from traffic; investigate the dependency instead of restart-looping every instance. |
| `GET /actuator/health/liveness` | Public, status only | Actuator alias of `/livez`. | Same as `/livez`. |
| `GET /actuator/health/readiness` | Public, status only | Actuator alias of `/readyz`. | Same as `/readyz`. |
| `GET /actuator/health` | Public, status only | Aggregate diagnostic health. It is not the deployment liveness contract. | Use for a coarse manual check only. |
| `GET /actuator/info` | Public, stable non-secret capability states | Whether each optional platform adapter is deliberately available or unavailable. | Treat an unavailable capability as a feature gate, not as whole-service unavailability. |
| `GET /actuator/prometheus` | Full CareOS authentication required | Prometheus scrape containing JVM, process, system, database-pool, HTTP, and bounded custom adapter metrics. | Scrape only after an approved non-interactive observability identity or private management boundary exists. |

Health responses expose only the aggregate `status`; component details and dependency configuration are suppressed. The default Compose backend health check uses `/readyz` so the frontend does not start before the identity dependencies are usable.

PostgreSQL and Redis are intentionally included in readiness because the current browser session and authorization foundation cannot provide a safe degraded mode without them. Optional document, scanner, notification, job, worker, scheduler, SMTP, and object-store capabilities are intentionally excluded. A business deployment must revisit readiness only if it introduces a real, tested degraded mode.

## Logs and request correlation

Spring Boot writes one ECS-compatible JSON object per console line. Runtime collection should ingest stdout/stderr without parsing multi-line text as a primary format.

Every HTTP request receives a validated `X-Correlation-Id`. The completion event uses these bounded fields:

| Field | Content |
| ----- | ------- |
| `correlationId` | Valid client correlation value or a generated UUID |
| `traceId`, `spanId` | OpenTelemetry context for cross-component correlation |
| `eventType` | Stable value `http.request.completed` |
| `httpMethod` | Allow-listed HTTP method or `OTHER` |
| `httpRoute` | Framework route template, such as `/api/v1/organizations/{organizationId}`, or `unmatched` |
| `httpStatus` | Numeric response status |
| `durationMs` | Request duration in whole milliseconds |
| `outcome` | `completed` or `exception` |

The completion logger never records the raw URI, query string, route parameter, body, cookies, authorization/CSRF headers, remote address, user agent, user identity, organization identity, notification recipient, or job/document payload. Do not add those values as log or metric dimensions. Exception logs may contain technical stack traces; application code must still avoid placing secrets or clinical content in exception messages.

Operational log access, retention, deletion, regional storage, immutability, and break-glass access remain owner-controlled production decisions.

## Metrics and traces

Micrometer publishes a Prometheus-format registry with a bounded `application="careos-backend"` tag. Existing Redis-job and durable-notification counters contain transition outcomes only; they do not contain tenants, recipients, payloads, document references, or deduplication values. HTTP metrics use framework route templates rather than raw request targets.

OpenTelemetry tracing is present with W3C propagation, baggage disabled, a default 10% sample probability, and explicit attribute/event/link limits. Trace export is **disabled by default**. OTLP metric and log export are also disabled, and ambient `OTEL_*` environment mapping is disabled so an inherited host setting cannot silently export CareOS telemetry.

The local configuration keys are:

```text
CAREOS_TRACE_SAMPLING_PROBABILITY=0.1
CAREOS_OTLP_TRACING_ENABLED=false
CAREOS_OTLP_TRACING_ENDPOINT=http://localhost:4318/v1/traces
```

Do not enable trace export until the collector endpoint, TLS/authentication, region, tenancy, field review, sampling/cost ceiling, retention, access, and outage behavior are approved. The localhost endpoint is inert while export is disabled; it is not a production collector recommendation.

The Prometheus endpoint currently follows the browser authentication boundary. It is therefore mechanically verified but not a production scraper integration. Do not make it anonymous. Production completion requires a separate non-interactive observability identity or a private management network with an approved trust boundary and attack tests.

## First response guide

| Observation | First interpretation | Initial checks |
| ----------- | -------------------- | -------------- |
| `/livez` is unreachable | Process, listener, network, or node failure | Container/process state, port/listener, recent deployment, memory/CPU exhaustion |
| `/livez` returns non-200 | In-process state cannot recover | Correlate the time with structured logs and traces; replace/restart the affected instance after capturing evidence |
| `/livez` is 200 and `/readyz` is 503 | PostgreSQL, Redis, or application readiness failure | Dependency health, connection saturation/timeouts, credentials/secret rotation, network policy, migration state |
| `/readyz` is 200 but an optional capability is unavailable in `/actuator/info` | Feature-specific adapter is disabled or failed its activation contract | Inspect the stable capability reason code and that adapter's configuration/runbook; keep the feature unavailable |
| HTTP 5xx rate or latency rises | Application or dependency degradation | Route-template metric, status, correlation ID, matching JSON log and trace; never search by patient data |
| Job/notification dead-letter counters rise | Durable transport has exhausted bounded retries | Preserve evidence and stop producers if growth is unsafe; replay is not authorized by this foundation |

Do not manually edit queue keys, notification rows, audit evidence, outbox content, or RLS settings during an incident. There is no approved dead-letter replay operation yet.

## Local probe drill

With the Compose stack running:

```bash
curl -i http://localhost:8080/livez
curl -i http://localhost:8080/readyz
docker compose pause redis
curl -i http://localhost:8080/readyz
curl -i http://localhost:8080/livez
docker compose unpause redis
curl -i http://localhost:8080/readyz
```

Expected behavior is `200/UP` for both probes initially, `503/DOWN` for readiness while Redis is paused, continued `200/UP` liveness, and readiness recovery after Redis resumes. Always unpause Redis in the same drill. The automated backend integration test performs this scenario against disposable infrastructure.

An anonymous metrics request must fail:

```bash
curl -i http://localhost:8080/actuator/prometheus
```

Expected status is `401`. Do not paste a real session cookie into scripts or shell history to bypass this boundary.

## Monitoring and alert acceptance checklist

The repository emits the source signals but does not configure a monitoring vendor, paging destination, or clinical escalation policy. Before production, owners must approve and test:

- sustained liveness/unreachable and readiness alerts with anti-flap windows;
- scrape absence, 5xx ratio, latency percentile, JVM/memory/CPU, database-pool, and Redis saturation signals;
- queue and notification dependency-error, retry, lease-recovery, and dead-letter signals once those adapters are approved;
- an on-call owner, severity model, acknowledgement/escalation route, maintenance suppression, and runbook link for every alert;
- synthetic login/tenant-isolation checks that use dedicated test identities and never production patient data;
- dashboards and alerts with only bounded-cardinality, non-sensitive labels;
- a delivery test proving alerts reach the intended owner and an outage test proving telemetry failure cannot block application requests.

Thresholds are deliberately not invented here. They require load evidence, service objectives, monitoring budget, and an approved response owner.

## Deployment and recovery acceptance checklist

Before calling any environment production-ready:

1. Provision managed PostgreSQL, Redis, private storage, secrets/KMS, TLS, network segmentation, and an approved telemetry sink.
2. Verify signed image/SBOM provenance and run backward-compatible migrations as a separately controlled job.
3. Configure startup, liveness, and readiness probes with failure thresholds appropriate to measured startup and dependency timeouts.
4. Run authenticated tenant-isolation and capability-denial smokes without real clinical content.
5. Validate log/metric/trace redaction, access, retention, regional storage, and telemetry-outage behavior.
6. Configure and exercise alert delivery, acknowledgement, escalation, and maintenance suppression.
7. Approve RPO/RTO, encrypted backup ownership, key availability, restore isolation, and evidence retention.
8. Perform a timed restore into an isolated environment, verify Flyway state and tenant isolation, reconcile outbox/job/notification state, and record achieved recovery point/time.
9. Exercise rollback or forward-repair without deleting audit/history data.

No production backup, restore, alert routing, dead-letter replay, deployment manifests, or external telemetry service is implemented by this repository. A passing local probe drill does not close those gaps.
