---
title: Observability
description: Export Rahdar's metrics, traces and logs over OpenTelemetry, read its logs as JSON, and import the Grafana dashboard and Prometheus alert rules that ship with every release.
---

# Observability

Rahdar can export its own metrics, traces and logs over OTLP, write its logs as JSON, and serve
Prometheus metrics. Nothing leaves Rahdar until you switch export on. Settings has a
**Rahdar health** section that shows jobs, broker nodes, the database pool and stream clients
without any of this set up.

## Turn on OTLP export

Export uses the standard [OpenTelemetry SDK environment variables](https://opentelemetry.io/docs/specs/otel/configuration/sdk-environment-variables/).
Set the collector's endpoint and switch on the signals you want:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318
OTEL_METRICS_EXPORTER=otlp
OTEL_TRACES_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

| Variable | Purpose |
| --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Collector base URL (OTLP over HTTP). `/v1/traces`, `/v1/metrics` and `/v1/logs` are appended. Each signal has its own `OTEL_EXPORTER_OTLP_<SIGNAL>_ENDPOINT`. |
| `OTEL_METRICS_EXPORTER`, `OTEL_TRACES_EXPORTER`, `OTEL_LOGS_EXPORTER` | Set to `otlp` to export that signal. Left unset, that signal is not exported. |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` (the default) or `grpc`, for traces and logs. Metrics always go over HTTP. |
| `OTEL_EXPORTER_OTLP_HEADERS` | Headers sent with every export, for example `authorization=Bearer …`. |
| `OTEL_TRACES_SAMPLER`, `OTEL_TRACES_SAMPLER_ARG` | Sampler, for example `parentbased_traceidratio` with `0.1`. Rahdar samples every trace by default; it is a low-traffic admin tool. |
| `OTEL_SERVICE_NAME` | Service name on everything exported. Defaults to `rahdar`. |
| `OTEL_RESOURCE_ATTRIBUTES` | Extra resource attributes, for example `deployment.environment=prod`. |
| `OTEL_SDK_DISABLED` | `true` turns the OpenTelemetry SDK off entirely. |

With the compose files, set them in `.env`: `.env.example` lists them, and `compose.prod.yaml` and
`compose.ha.yaml` pass them to Rahdar. An empty variable counts as unset.

Compression, timeout and the other `OTEL_EXPORTER_OTLP_*` variables work as the specification
describes. The sampling probability can also be set with `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`.

## What is traced

A trace follows an HTTP request or a background job run through everything it causes:

- the HTTP request;
- each **Jolokia** management call (`jolokia`), tagged with the node's `host:port` and its outcome;
- each **Core** call (`core browse`, `core send`, and `core receiver.open`, `core sender.open`,
  `core receiver.commit` / `core sender.commit` for a drain or a transfer), tagged with the node and
  the operation;
- each **messaging** operation of a long-lived broker link (a plugin consumer, a capture, a relay): a
  send, a receive, a handler's processing and a settlement, named and tagged by the OpenTelemetry
  messaging conventions (`messaging.system`, `messaging.destination.name`, `messaging.operation.type`);
- each **job** run (`job <id>`), so a scrape tick is a root span with its Jolokia calls beneath it;
- each **database** query, with its statement text and never its parameter values.

Tracing inside the broker is out of scope: a span ends at Rahdar's side of the call. Rahdar never
writes trace context (`traceparent`) into a message: a message it sends, moves, retries or relays keeps
exactly the properties its producer gave it, and a producer's own trace context is never overwritten.

## Nothing secret is exported

Exported spans, attributes and logs go through the same redaction as Rahdar's console log
(ADR-0133). A credential in a log line, an exception message or a span attribute leaves as
`[redacted]`. Message payloads, message headers, management call arguments and database parameter
values are never recorded, and a broker URL is reduced to `host:port` before it becomes a tag.

## JSON logs with trace ids

Set `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` and the console log becomes one
[ECS](https://www.elastic.co/guide/en/ecs/current/) JSON object per line, already redacted. Each
line of a traced request carries `traceId` and `spanId`, equal to the ids of the exported trace,
so a log search can jump to the trace and back. Other formats Spring Boot supports (`logstash`,
`gelf`) work the same way. Trace ids reach the log lines even when OTLP export is off.

## Metrics

`/actuator/prometheus` serves these, and an OTLP pipeline with the default name translation yields
the same names. `node` is always the broker's `host:port`, never its URL.

| Metric | Meaning |
| --- | --- |
| `rahdar_job_lag_seconds{job_id}` | Seconds a background job is past its interval since it last completed; zero on schedule, `NaN` until its interval is known. |
| `rahdar_job_degraded{job_id}` | 1 while no run has finished within three of the job's intervals, else 0. The stalled-job alert uses it. |
| `rahdar_job_seconds_{count,sum,max}{job_id,feature,error}` | Job runs. `error` is `none` for a run that succeeded. |
| `rahdar_broker_management_seconds_{count,sum,max}{node,outcome,error}` | Jolokia management calls. `outcome` is `SUCCESS` or `ERROR`. The series with `quantile="0.95"` is the 95th percentile. |
| `rahdar_broker_requests_total{node}` | Management requests Rahdar admitted to a node. |
| `rahdar_broker_calls_total{kind}` | Management requests Rahdar sent to brokers, by kind: `single`, `batch` (a batched read), `core` (the Core client) or `transfer`. |
| `rahdar_broker_permit_wait_seconds_{count,sum,max}{node}` | Time requests waited for that node's rate ceiling. |
| `rahdar_broker_permit_timeouts_total{node}` | Requests refused because the ceiling stayed full for 5 seconds. |
| `rahdar_broker_core_seconds_{count,sum,max}{node,operation,error}` | Core transport calls. |
| `rahdar_broker_links{state}` | Broker links (plugin consumers, captures, relays) by state: `open`, `recovering` or `failed`. A link that stays `recovering` has lost its node. |
| `rahdar_broker_link_transitions_total{state}` | Broker link state changes, by the state entered. A rising `recovering` count is a flapping connection. |
| `rahdar_rr_latency_seconds{cluster,address}` | Request-reply latency, with 0.5, 0.95 and 0.99 quantiles. |
| `rahdar_stream_clients` | Event stream clients connected to this instance. |
| `hikaricp_connections_{active,idle,max,pending}` | Rahdar's database pool. |
| `jvm_threads_live_threads` | Live threads in Rahdar. Steady in normal operation. |

`/actuator/prometheus` is not authenticated, as before: keep it off the public network.

## Dashboard and alert rules

Every release ships a Grafana dashboard and Prometheus alert rules, checked in CI against the
metric names above:

- in the repository: [`deploy/observability/`](https://github.com/sudoitir/rahdar/tree/main/deploy/observability);
- on each GitHub release: `rahdar-observability-<version>.tar.gz` and its `.sha256`.

**Dashboard.** In Grafana, *Dashboards*, *New*, *Import*, upload
`grafana/rahdar-overview.json` and pick your Prometheus data source. It has `node` and `job`
filters, and panels for job lag and failures, management call rate, latency and outcome, rate-limit
waits and timeouts, Core latency, the database pool, stream clients and threads.

**Alert rules.** Add `prometheus/rahdar-alerts.yml` to `rule_files` in `prometheus.yml` and reload.
It alerts on a job stalled or failing, a node failing more than 10% of its management
calls, slow management calls (p95 over 2 s), permit timeouts, and a waiting or nearly exhausted
database pool, and more than 500 live threads for 15 minutes (Rahdar's threads are bounded by
configuration, so steady growth points at a leak). Check a copy you edited with `promtool check rules rahdar-alerts.yml`.

## Upgrading

These metric changes break existing queries:

- the `node` tag is `host:port`; it was the full Jolokia URL;
- `rahdar_rr_latency_seconds` is now `rahdar_rr_latency_seconds`;
- `rahdar_job_seconds` gains an `error` tag (`none` when the run succeeded).

The shipped dashboard and rules use the new names.
