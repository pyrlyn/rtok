# `rtok discover` — shell commands nothing filters

Port of `src/measure/discover.rs` and `src/cli/discover.rs` (T688). Code: `rtok/src/nativeMain/kotlin/rtok/measure/Discover.kt`,
`model/StatsPage.kt` (`discover`), `cli/DiscoverCmd.kt`.

## How it works
Reads the newest 50 000 stored `PostToolUse` hook bodies within `--since` (default `30d`). For every Bash call
(`rtok run` wraps stripped, `rtok`'s own commands skipped) it takes the command family (`bashFamily`) and asks the
`cmd` plugin whether a rule or a formatter covers it (`Formatters.filterKind`). Uncovered families are ranked by the
output tokens the agent read (`stdout` + `stderr` bytes / 4), then by calls, then by name; `--limit` (20) keeps the
top rows. Each row names the command behind its largest output, first line, 80 characters. Nothing is re-run and
nothing is claimed saved. `--json` prints `{scanned, covered, unreadable, gaps: [{family, calls, bytes, est_tokens, example}]}`.

## Enable / disable
A read-only report; it needs the hook to have stored bodies (`[core] store_raw` or bodies under `call_io_inline_bytes`).
Rows over the inline cap count as `unreadable`.

## Test
`./gradlew :rtok:macosArm64Test` — `DiscoverTest` (7, all of discover.rs); `GuardCheckE2eSpec` runs `rtok discover`
over rows the binary recorded through `rtok hook PostToolUse` (`./gradlew e2eTest`); `GuardCliSpec` covers the empty store.

## Limitations
None known against Rust.
