# Debugging with OpenTelemetry (self-tracing)

`RTOK_TRACE=1` makes every rtok process send spans of its own work to an OTLP/HTTP collector
(Jaeger, Grafana Tempo, SigNoz, an OpenTelemetry Collector). Use it to see where a slow hook spends
its time, which plugin failed, or what an MCP server did before it stalled.

This is a debugging aid of the Kotlin port, separate from `rtok otel` (the export of the agents'
ledgers, `[otel]` in the config). Self-tracing does not touch the store, stdout or the log format,
and it is off by default: with `RTOK_TRACE` unset rtok makes no network call and records nothing.

## Quick start: Jaeger on Colima

```bash
colima start
docker run --rm -d --name rtok-jaeger -p 16686:16686 -p 4318:4318 jaegertracing/jaeger:2.11.0
RTOK_TRACE=1 rtok info
open http://localhost:16686   # service "rtok"
```

For hooks and MCP servers that an agent host starts, put the variable into the environment the host
passes on, e.g. `export RTOK_TRACE=1` before starting the host.

## Environment

| Variable | Default | Meaning |
| --- | --- | --- |
| `RTOK_TRACE` | unset | `1`, `true`, `on` or `yes` turns tracing on |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | — | Full traces URL, used as is |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Base URL; `/v1/traces` is appended |
| `OTEL_EXPORTER_OTLP_HEADERS` | — | `k=v,k2=v2`, e.g. an ingestion key |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | `2000` | POST timeout, ms; short so a missing collector cannot stall a hook |
| `OTEL_BSP_SCHEDULE_DELAY` | `5000` | Flush interval of long-lived processes (`mcp`, `proxy`, `demon`, `web`), ms |
| `OTEL_SERVICE_NAME` | `rtok` | `service.name` of the resource |
| `TRACEPARENT` | — | W3C trace context; the process's root span becomes its child |

`OTEL_EXPORTER_OTLP_ENDPOINT` also switches on `rtok otel` ledger export when `[otel] endpoint` is
empty. To trace rtok itself without exporting ledgers, leave it unset (the default
`localhost:4318` is what Jaeger listens on) or use `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.

## What is traced

| Span | Where | Attributes |
| --- | --- | --- |
| `rtok <command path>` | every process, the root (e.g. `rtok hook`, `rtok otel flush`) | `process.exit.code` |
| `hook <event>` | `rtok hook <event>` and each resident request | `rtok.hook.event`, `rtok.hook.host` |
| `plugin <id>` | each hook plugin call | `rtok.plugin.id`, `rtok.hook.event` |
| `hook serve` | each request to the resident (`rtok hook --serve`), a trace of its own | `rtok.hook.client_pid` |
| `mcp tool <name>` | each MCP `tools/call`, a trace of its own | `rtok.mcp.tool`, `rtok.mcp.ok` |
| `proxy <method> <path>` | each forwarded proxy request, a trace of its own, ended when the upstream stream is done | `http.request.method`, `url.path`, `rtok.lane`, `rtok.upstream` (no query), `rtok.provider`, `http.response.status_code` |

Spans carry names, ids and timings, not prompts, tool arguments or file contents. A failure sets the
span's error status and adds an `exception` event with the type and message; a command that fails
marks its root span, and an MCP tool that answers an error marks its span.

Long-lived processes (`mcp`, `proxy`, the hook resident) start one trace per request: their root span
is only posted when the process exits, so it would otherwise hold every request of the session.

The detached children a hook starts (`rtok otel flush --coalesce`, the deferred `SessionEnd`) get
`TRACEPARENT`, so their spans join the hook's trace.

## Delivery

A short-lived command posts its spans once, on exit. Every traced process also posts from a background
worker every `OTEL_BSP_SCHEDULE_DELAY`, which is what delivers the spans of long-lived ones; a process
killed by a signal loses only what that worker had not posted yet. At most 8192 spans wait in memory; beyond that new spans are
dropped and the next batch reports `rtok.trace.dropped_spans` on its resource. A collector that is
down or answers non-2xx costs at most one timeout per post and one `warn` line from source `trace` in
the rtok log (`rtok logs`); the command itself never fails because of tracing.
