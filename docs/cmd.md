# Cmd plugin (in progress)

Port of `src/plugins/cmd` (10k lines, 207 Rust tests), file by file. Code: `rtok/src/nativeMain/kotlin/rtok/plugins/cmd/`.

## Ported so far
| Rust | Kotlin | Tests |
|---|---|---|
| `run.rs` `sh_quote`, `wrap_quote` | `Run.kt` (`shQuote`, `wrapQuote`) | via guard tests |
| `logfmt.rs` | `Logfmt.kt` (`logfmtSummary`, `logfmtFactor`) | 8 → 8 |
| `gh.rs` | `Gh.kt` (`Gh.format`: `pr/issue view`, `pr checks`, `run view --log`) | 7 → 7 |

## How it works
- **logfmt**: a run of ≥ 8 `key=value` lines becomes one summary (constant fields once, varying keys with a
  `first..last` range, at most 12 keys); `logfmtFactor` strips pairs every record shares, leaving anchor
  lines (errors) whole, and returns an `[every record: …]` header.
- **gh**: `view` drops empty `field:\t` rows and blank-line runs in the body; `checks` prints a state count
  and only non-passing rows; `run view --log` keeps each problem line (error/failed/warning/…) with 2 lines
  before and 4 after, drops timestamps, and counts quiet steps. Output that is not shorter is left alone.

## Enable / disable
Part of the cmd plugin (`[plugins.cmd] enabled`), which is registered once `CmdPlugin`, `formatters.rs`,
`rules.rs`, `run.rs` and `hook.rs` are ported. Until then these formatters are library code only.

## Test
`./gradlew :rtok:macosArm64Test` (`LogfmtTest`, `GhTest`).

## Limitations
- Not yet wired into `rtok run` / the PreToolUse rewrite; remaining files: bounded, catfile (needs the read
  cache), compact, diffs, filter, formatters, grep, hook, loops, mise, python, rules, run.
