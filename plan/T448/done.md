`rtok run` read the command's stdout and stderr into memory with no limit (`proc::capture`), then copied the body several times (the merged body, a lossy `String`, the rule engine's lines, the filtered text). A multi-GB `cat big.log` or `yes | head -c 10G` ran `rtok run` out of memory, where the unwrapped host command would stream and be cut at ~30 000 characters.

Fix: `proc::capture_capped` keeps at most `CAPTURE_CAP` (32 MiB) of stdout and stderr together; `capture` is the same call without a cap, so the host `--version` probe is unchanged. Past the cap the pipes are still read and counted, so the child never blocks on a full pipe and exits with its own code. A capped body ends with `[rtok: output capped at 32 MiB; N bytes not captured]`, so the archive (`expand`) says it is partial. 32 MiB is three orders of magnitude above what the host shows an agent, and keeps the peak of the copies in the low hundreds of MiB.

Check: `capture_capped_keeps_the_cap_and_counts_the_rest`, `capture_capped_under_the_cap_drops_nothing` (`src/proc.rs`), `capture_body_caps_and_appends_the_notice`, `capture_body_without_overflow_has_no_notice` (`src/plugins/cmd/run.rs`).

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
