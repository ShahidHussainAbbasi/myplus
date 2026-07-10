# Observability stack — self-hosted, free, secure (Loki + Tempo + Grafana + OTel Collector)

Logs **and** traces for every service, aggregated and correlated, running on the same VPS at **$0**.
Apps are instrumented **Spring-natively** (no per-JVM Java agent) via `opentelemetry-spring-boot-starter`
and export OTLP to the collector. See the design in
[`../docs/observability-otel-grafana-design.md`](../docs/observability-otel-grafana-design.md).

```
apps (gateway + 14 services)  --OTLP-->  otel-collector  --> Loki (logs, 7d)
                                                          --> Tempo (traces, 3d)
                                              Grafana (localhost:3000, SSH-tunnel only)
```

## What's included / not
- **In:** traces (Tempo) + structured logs (Loki) + **metrics (Prometheus)**, one Grafana with
  auto-provisioned datasources, log↔trace correlation, and a provisioned RED+JVM dashboard. Instruments
  the gateway + all 14 `service-parent` services. **Per-tenant** `org_id`/`user_id` are stamped onto
  spans, logs (MDC), and baggage by `TenantTelemetryFilter` (`common-security`), so traces and logs are
  filterable by tenant across the saga. The **monolith** UI is instrumented too (its own pom +
  `application.properties`) and roots the trace; it stamps `user_id` (`MonolithTelemetryFilter`).
- **Metrics path:** pushed over **OTLP** (like logs/traces) → collector → Prometheus' native OTLP
  receiver. No `/actuator/prometheus` scraping, so no actuator-exposure or scrape-target changes.
- **Not yet:** gateway (reactive) baggage (services already stamp from the forwarded `X-Org-Id`, so
  saga spans/logs are covered).

## Per-tenant filtering (the payoff)
- **Logs:** in Loki, filter `{service_name="business-service"} | org_id="42"` — `org_id`/`user_id`
  arrive as structured metadata (captured from MDC).
- **Traces:** in Tempo search, filter by span attribute `org_id = 42`.
- If MDC keys don't appear in Loki, the appender's MDC capture may need the logback-xml form instead of
  the `otel.instrumentation.logback-appender.experimental.capture-mdc-attributes` property — see
  Troubleshooting.

## Security model (why it's safe on a public VPS)
- **Grafana is bound to `127.0.0.1:3000` only** — never published to the internet. You reach it through
  an SSH tunnel. No public port, so **no TLS cert needed** (= free) and nothing to brute-force.
- **Loki / Tempo / Prometheus / Collector have NO host ports** — reachable only on the private
  `myplus-net`. Their open (unauthenticated) OTLP/HTTP endpoints are therefore never internet-reachable.
- **Set `GRAFANA_PASSWORD`** in `microservices/.env`. ⚠️ If unset, Grafana falls back to **`admin`/`admin`**
  — the localhost binding is the real control, but set a strong value anyway (defense in depth).
- **Telemetry is off by default** (`otel.sdk.disabled`), so nothing exports unless you layer the overlay
  — no accidental data egress.
- **Treat telemetry as sensitive:** app logs/traces may carry PII/tenant data. Retention is capped
  (Loki 7d, Tempo 3d, Prometheus 15d) so the exposure window and disk are bounded; ensure the app never
  logs secrets/JWTs. Keep the observability images updated for CVEs.
- If you front Grafana with nginx instead of the SSH tunnel, add **HTTP basic-auth + TLS** (see the
  nginx note below) — never expose `:3000` unauthenticated.

## Performance / tuning
Designed to stay light on the shared box:
- **Off by default → zero overhead** when the overlay isn't running.
- **Non-agent instrumentation** (Spring starter, not the OTel Java agent) → lower per-JVM cost than a
  per-process agent across ~16 JVMs.
- **Traces are sampled 10% in prod** (`parentbased_traceidratio`, `TRACE_SAMPLE`); dev = 100%. Metrics
  export on a 60s interval. Spans/logs are **batched** by the SDK and again by the collector, so network
  overhead is low.
- **Cardinality-safe by design (important):** tenant `org_id`/`user_id` are **span attributes + Loki
  structured-metadata + baggage — NOT Prometheus labels and NOT Loki stream labels.** Only low-cardinality
  `service_name`/`service_namespace` are promoted to metric labels. This deliberately avoids the
  cardinality explosion that per-tenant labels would cause in Prometheus/Loki.
- **Bounded footprint:** the collector has a `memory_limiter` (sheds load rather than OOM under a burst),
  and every backend has a `mem_limit` (loki/tempo/prometheus/grafana 512m, collector 256m).
- **RAM headroom:** full app stack (~16 GB) + observability (~2 GB) ≈ **18 GB**. On a 16 GB box, run the
  POS subset with observability (Option B in the runbook) or add swap to avoid thrashing.
- **Tuning knobs:** `TRACE_SAMPLE` (sampling), retention in `loki-config.yaml`/`tempo-config.yaml`/the
  prometheus `--storage.tsdb.retention.time` flag, and the per-container `mem_limit`s. Under heavy log
  volume, keep prod `root: WARN` (already set) or raise the collector's `mem_limit`.

## Opt-in by design (off unless layered)
Telemetry export is **off by default** (`otel.sdk.disabled` defaults to `true`). The base stack
(`docker compose -f docker-compose.yml up`) makes **no** export attempts — so no
`UnknownHostException: otel-collector` noise when the backend isn't running. This overlay sets
`OTEL_SDK_DISABLED=false` on each app service, so telemetry turns on **only** when you layer it in.
Always use the layered command below to run with observability.

## Resource footprint
~2.0–2.3 GB total (loki 512m + tempo 512m + prometheus 512m + grafana 512m + collector 256m). Fits the
16 GB box alongside the app.

---

## Local test

1. **Set a Grafana password** in `microservices/.env`:
   ```dotenv
   GRAFANA_PASSWORD=<something-strong>
   ```
2. **Build the instrumented jars** — the microservices reactor **and** the monolith (its jar is built
   separately from the repo root):
   ```bash
   cd microservices && mvn -q -DskipTests install     # gateway + 14 services
   cd ..            && mvn -q -DskipTests clean package # monolith jar (-> target/myplus.jar)
   ```
   > If Maven can't resolve `opentelemetry-spring-boot-starter`, bump
   > `<opentelemetry-instrumentation.version>` in `microservices/pom.xml` **and** the matching
   > `opentelemetry-instrumentation-bom` version in the root `pom.xml` to the latest release, then rebuild.
3. **Bring up app + observability** (layer both compose files so they share `myplus-net`):
   ```bash
   docker compose -f docker-compose.yml -f observability/docker-compose.observability.yml up -d --build \
     loki tempo otel-collector grafana \
     mysql redis eureka-server config-server api-gateway \
     auth-service notification-service catalog-service inventory-service business-service finance-service monolith
   ```
4. **Generate traffic:** open `http://localhost:8080`, do a **sale** (the gateway→business→inventory→finance saga).
5. **Look:** open **http://localhost:3000** (admin / `GRAFANA_PASSWORD`) → **Explore**:
   - **Tempo** → *Search* → recent traces → one trace spanning the services.
   - **Loki** → `{service_name="business-service"}` → logs; a line with a `trace_id` links to its trace.

---

## Deploy to the Hostinger VPS (permanent)

```bash
ssh root@187.127.125.91
cd /opt/myplus && git pull
cd microservices
# GRAFANA_PASSWORD must be set in microservices/.env
mvn -q -DskipTests install                                   # rebuild instrumented jars
docker compose -f docker-compose.yml -f observability/docker-compose.observability.yml up -d --build \
  loki tempo otel-collector grafana \
  mysql redis eureka-server config-server api-gateway \
  auth-service notification-service catalog-service inventory-service business-service finance-service monolith
docker compose -f docker-compose.yml -f observability/docker-compose.observability.yml ps
```

**Reach Grafana securely (no public exposure)** — from your laptop:
```bash
ssh -L 3000:localhost:3000 root@187.127.125.91
# then browse http://localhost:3000 on your laptop
```

> Make the layered command your standard `up`/`ps`/`logs` invocation on the VPS so observability stays
> up permanently (or add an alias). `ufw` already blocks stray ports; Grafana isn't published anyway.

### Optional: Grafana behind your existing nginx (instead of SSH tunnel)
If you'd rather browse `https://maxtheservice.com/grafana/`, add an nginx `location /grafana/` proxying to
`127.0.0.1:3000`, set `GF_SERVER_ROOT_URL=https://maxtheservice.com/grafana/` +
`GF_SERVER_SERVE_FROM_SUB_PATH=true` on the grafana service, and **add HTTP basic-auth** in nginx. The
SSH-tunnel option above needs none of this and is the default.

---

## Acceptance checks (from the design doc, T1–T5)
| # | Do | Expect |
|---|----|--------|
| T1 | Successful sale | One Tempo trace across the services |
| T2 | `OUT_OF_STOCK` sale | Inventory span `status=ERROR` + correlated ERROR log (same `trace_id`) |
| T3 | Loki `{service_name="finance-service"}` | Finance logs, clickable through to their trace |
| T4 | `docker stats` while running | Observability containers ≤ their `mem_limit`; app unaffected |
| T5 | Leave it a day | Loki/Tempo/Prometheus disk stays bounded (7d/3d/15d retention) |
| T6 | Open the **MyPlus — Services (OTLP)** dashboard after some traffic | Request-rate / p95 / 5xx / JVM-heap panels populate per `service_name` |

## Tuning
- **Trace volume:** prod samples 10% (`TRACE_SAMPLE` env, set in `application-prod.yml`); local/dev = 100%.
- **Retention:** `loki-config.yaml` `retention_period` (7d) and `tempo-config.yaml` `block_retention` (3d).
- **Turn a service's telemetry off:** set `OTEL_SDK_DISABLED=true` on that container.

## Troubleshooting
| Symptom | Fix |
|---------|-----|
| No traces in Tempo | Confirm the service was rebuilt with the starter; check `docker compose logs otel-collector`; verify `OTLP_ENDPOINT` resolves to `http://otel-collector:4318` on `myplus-net`. |
| No logs in Loki | Loki needs `allow_structured_metadata: true` (set); check collector `otlphttp/loki` endpoint is `http://loki:3100/otlp`. |
| Maven can't find the starter | Bump `<opentelemetry-instrumentation.version>` in the root pom. |
| Grafana unreachable | It's localhost-only by design — use the SSH tunnel, or wire nginx (above). |
| `UnknownHostException: otel-collector` / `Failed to export spans` spam | The app ran with telemetry on but no collector. Either run the **layered** command (starts `otel-collector`), or you're on old jars from before the off-by-default fix — rebuild config-server + monolith. Base stack (no overlay) is silent by design. |
| Dashboard panels empty | Metrics arrive via OTLP; exact names depend on the OTel Java instrumentation version (e.g. `http_server_request_duration_seconds_count`, `jvm_memory_used_bytes`). Open Grafana → Explore → Prometheus, list metrics, and adjust the dashboard queries if a name differs. Confirm Prometheus was started with `--web.enable-otlp-receiver`. |
| `org_id`/`user_id` missing on log lines in Loki | The auto-installed logback appender may not honour the `capture-mdc-attributes` property on this starter version. Fallback: add a `logback-spring.xml` with the OpenTelemetry appender and `<captureMdcAttributes>org_id,user_id</captureMdcAttributes>`. Traces still carry the attrs regardless, and each log links to its trace. |
