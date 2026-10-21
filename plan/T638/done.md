Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` in [run_bounded](src/bench.rs#L402). The deadline controls only child polling; the subsequent `reader.join()` waits without a limit for stdout EOF. With the actual extracted function, a synthetic `sh -c 'sleep 0.8 & printf parent; exit 0'` and a 100 ms limit took 816 ms, waiting for the descendant after the direct child had exited. The ordinary `printf control` case returned its complete output. A longer-lived inherited pipe can stall the benchmark indefinitely. T235.1 and the host-version follow-up fixed command capture through `src/proc.rs`; this separate benchmark helper still implements its own unbounded drain. T620 concerns LSP request framing, not this process reader.

Execution plan:

1. Add hermetic subprocess regressions for an exited child whose descendant holds stdout, a child exceeding its deadline, nonzero exit and ordinary complete output. Never launch a real agent.
2. Reuse the shared bounded capture mechanism in `src/proc.rs`, preserving the benchmark's success/timeout result semantics and imposing a finite post-exit drain.
3. Reap the direct child on errors and timeout, and check both ordinary and graph benchmark callers. Keep capture output bounded where the shared API supports it.
4. Run benchmark/process tests and `just check`.

Check: the benchmark finishes within its deadline plus a documented finite drain allowance even when descendants retain stdout. The reproduction compiled the actual helper and ran synthetic shell children on macOS; no live benchmark agent or Windows process path was run.

Result: `run_bounded` in `src/bench.rs` captures through `proc::capture_capped` (finite drain after exit, 8 MiB cap), so a descendant holding the pipe no longer stalls the bench; stdin is now null.

Check: `a_descendant_holding_the_pipe_does_not_stall_the_bench` failed before (5.05 s) and passes after; `a_failing_run_is_not_a_result`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
