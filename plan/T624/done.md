Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [otel-check.sh](tools/otel-check.sh#L72). After `rg` matches a complete `"traceID":"<32 hex digits>"` field, `sed 's/.*"//;s/"$//'` greedily removes everything through the final quote. Even a valid trace therefore becomes an empty ID and the following check fails before trace validation. Running the exact pipeline on `0123456789abcdef0123456789abcdef` produced an empty result; the malformed-ID control was also empty. T53.4 introduced the check but does not cover this extraction defect.

Execution plan:

1. Add an offline fixture test proving that a valid Jaeger response yields the exact trace ID and a missing/invalid ID fails clearly.
2. Parse the response structurally using an existing available JSON parser, and explicitly select a valid trace ID rather than depending on quote stripping or compact JSON formatting.
3. Cover compact/pretty JSON, multiple traces, empty data and malformed responses; keep these checks independent of Docker.
4. Run the extractor tests and `just check`; run the existing Docker-backed OTel check when that environment is available.

Check: a valid returned trace can reach the existing trace-validation stage. Only the exact extraction pipeline was executed with synthetic JSON; the Docker/collector/Jaeger integration was not run.

Result: `tools/otel_trace_id.py` parses the Jaeger JSON and prints the first valid 32-hex trace id (exit 1 with a message otherwise); `tools/otel-check.sh` calls it instead of the `sed` pipeline.

Check: `tools/tests/test_otel_trace_id.py` (11). Not run against a real Jaeger.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
