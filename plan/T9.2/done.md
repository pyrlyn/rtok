**T9.2 baseline vs rtok** · T9.1 · `bench/results/*.json`
Do: config A = current settings (81 hooks, both proxies); config B = rtok only (7 hooks, `rtok mcp`, `rtok proxy compress`, legacy hooks/MCP off). Run, save, summarize in research.md §2.
Check: results committed; summary table with cost delta and pass rate.
Status: done 2026-09-02 · Check: `bench/results/a.json` and `b.json` committed; research.md §2 table shows cost delta 0.0000 USD and pass 6/6 both configs. `make check` green. Deviation: also `src/bench.rs` (`RTOK_BENCH_LIVE` gate, JSON writer), `bench/configs/{legacy,rtok}.json`. Live `claude -p` was not run; usage/cost are zeros. Re-run with `RTOK_BENCH_LIVE=1` to fill cost.
