**T34.2 latency gates use nearest-rank p95** · T2.2 · `tests/common/mod.rs`, `tests/otel.rs`, `tests/latency.rs`, `tests/graph_bench.rs`
Do: three gates took `samples[n * 95 / 100]`, one rank high — at n = 20 (the debug otel gate) that is the maximum, so one slow spawn failed it.
Check: one `common::p95` (`ceil(0.95·n) − 1`) in all three.
Complexity: 1/5
Status: done 2026-09-10 · Model: Opus 5
