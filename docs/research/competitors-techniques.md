# Competitor techniques: sqz, lean-ctx, token-optimizer, caveman

Goal: rtok saves more tokens than the best of these four on every shell category, without
dropping commit hashes, paths, or WARN/ERROR lines. Measurements: draft #1076 (`a449e6a3`,
`docs/comparison/rtok-vs-{sqz,lean-ctx,token-optimizer,caveman}.md`). `git log` fix: draft #1079.
Frequency: local `~/.rtok/rtok.db`, PostToolUse Bash, 2026-10-12..15, 16,491 unique commands
in 363 categories (first utility after `cd` / `rtok run` is peeled).

Nothing below is copied. For each technique we name the idea, link the source we read, and
specify an rtok design that should beat it. Expected gains are targets to verify with the
#1076 harness; none are measured yet.

## Sources and licenses

| Tool | Source studied | License | Code read? |
|---|---|---|---|
| sqz | [ojuschugh1/sqz@cf2b03c8](https://github.com/ojuschugh1/sqz/tree/cf2b03c8072729f366e48a8a4933a421bd2a1e79) | MIT OR Apache-2.0 | yes |
| lean-ctx | [yvgude/lean-ctx@50d68e56](https://github.com/yvgude/lean-ctx/tree/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06) | Apache-2.0 for `rust/**` (`LICENSE_MATRIX.toml`; `rust/src/commercial`, `team`, `enterprise` are not public and were not read) | yes |
| token-optimizer | [alexgreensh/token-optimizer@9b129154](https://github.com/alexgreensh/token-optimizer/tree/9b12915453c4fc7307b03b9406c2ebcfb747ffd7) | PolyForm Noncommercial 1.0.0 + small-business permission | **no**: README, BENCHMARK.md and measured behaviour only |
| caveman / caveman-shrink | [JuliusBrussee/caveman@d262ab38](https://github.com/JuliusBrussee/caveman/tree/d262ab3882952a59312ff3537cd86d894523c5ab) (the shallow clone of tag `v4.0.1` resolved to this commit) | Apache-2.0 (`shrink/LICENSE`, `shrink/BINARY_LICENSE.md`) | yes |

## Key techniques per tool

### sqz (as found in its source)
- Pipeline of stages over JSON and text: keep/strip fields, strip nulls, flatten, truncate strings,
  collapse arrays ([stages.rs](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/stages.rs#L39-L480)).
- Diff fold: keep +/-/headers and N=2 context lines, replace the rest with `[N unchanged lines]`
  ([stages.rs#L664](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/stages.rs#L664-L755)); per-command git diff keeps 1 context line
  ([cmd_formatters/git.rs#L322](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/git.rs#L322)).
- Word abbreviation table (`configuration`→`config`, `function`→`fn`)
  ([stages.rs#L485](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/stages.rs#L485-L620)).
- git status as `staged(N): a, b` lines, untracked capped at 3 + count
  ([git.rs#L213](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/git.rs#L213-L258)); git log as `sha7 subject` with body kept
  ([git.rs#L290](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/git.rs#L290-L318)).
- grep: print each path once, then `line:text` under it; bail if < 10 % saved
  ([system.rs#L7](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/system.rs#L7-L49)).
- Safety net: reject a formatted result that drops protected or error lines
  ([cmd_formatters/mod.rs#L37](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/mod.rs#L37-L79)).
- Cross-call dedup: SHA-256 of content, re-sends become `§ref:HASH§` (~13 tokens), with staleness by turn and age
  ([cache_manager.rs#L17](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cache_manager.rs#L17-L160)).
- Token-level RLE `tok [×N]` ([rle_compressor.rs](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/rle_compressor.rs#L27)), TOON for JSON ([toon.rs](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/toon.rs#L61)).

### lean-ctx (found in its source, measured 2026-10-14)
- ~95 per-command pattern modules ([core/patterns/](https://github.com/yvgude/lean-ctx/tree/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns)); every result goes through
  "only if fewer tokens" ([mod.rs#L207-L215](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/mod.rs#L207)); ANSI strip first ([mod.rs#L90](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/mod.rs#L90)).
- git status: one line per section with sigils `+a ~b -c →r`, branch with `↑N`, `clean`
  ([git/status.rs#L14-L120](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/git/status.rs#L14)). This is why it wins `git status` (−40.5 %).
- git diff: drop `index` lines, max 3 context lines per run; above 500 lines keep 200 head + 50 tail per file
  ([git/diff.rs#L25](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/git/diff.rs#L25)).
- git log summary: `sha7 subject`, totals `[N commits, +A/-D]` from stat/numstat/hunks
  ([git/log.rs#L219](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/git/log.rs#L219)).
- grep: group by file, sort files by match count, cap 10 (5 if > 200) matches per file, trim content to 120 chars,
  shorten paths, header `N matches in kF` ([grep.rs#L17](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/grep.rs#L17-L128)).
- ls -l: drop perms/owner/date, `name  size` with human sizes, dirs first with `/`
  ([ls.rs#L18](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/ls.rs#L18-L63)). This is why it wins `ls -la` (−66.6 %).
- Logs: strip timestamps, collapse consecutive repeats `(xN)`, list errors first, head 5 + tail 10 with explicit omission marker
  ([log_dedup.rs#L53](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/core/patterns/log_dedup.rs#L53)).
- `cat f`, `sed -n a,bp f`, `awk NR..`, `head -n f` are rewritten to its own `read` tool with modes
  (map/signatures/lines) ([hook_handlers/file_rewrite.rs#L440](https://github.com/yvgude/lean-ctx/blob/50d68e5648c21bf4880571c6a8e8ad96f8ef9b06/rust/src/hook_handlers/file_rewrite.rs#L440)). This is why it wins `cat rules.rs` (−91.4 %).

### token-optimizer (behaviour and docs only, measured 2026-10-14)
- Allow-list of ~111 read-only commands in 22 families; others pass through (`cat` refused, `git log`, `find` untouched).
- Archives the full original and appends an expand pointer (README "Bash Output Compression").
- Diff summary for big diffs: −97.9 % on `git diff HEAD~5` but only 51 % of changed paths kept; worktree diff −79 % with all paths. Inferred: per-file header + change counts, hunks dropped above a size threshold.
- Delta re-reads: a re-read file returns only the diff (README "Delta Mode"); code re-reads become a skeleton.
- grep −89.7 %: inferred grouping by file + cap per file, like lean-ctx.

### caveman (caveman-shrink, found in its source, measured 2026-10-14)
- MCP tool-catalog shrink: keep schema structure and constraint words, cut descriptions to the first sentence(s), byte cap
  ([toolschema.go#L270](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/engine/compressors/toolschema.go#L270)); exact original stored in a durable recovery store with a handle
  ([shrink.go#L117](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/shrink/shrink.go#L117)). Wins −10..−29 % on catalogs where rtok does 0 %.
- Terminal: strip ANSI, collapse `\r` progress redraws to final state, keep head 3 + tail 3 + every error/trace line
  ([terminal.go#L58](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/engine/compressors/terminal.go#L58)).
- Logs: elided runs get a field summary (logfmt keys/values that are constant across the run)
  ([log.go#L49-L75](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/engine/compressors/log.go#L49)).
- Exact repeat runs (≥ 3) → one line + count ([repetition.go#L43](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/engine/compressors/repetition.go#L43)); diff context 2 ([diff.go#L24](https://github.com/JuliusBrussee/caveman/blob/d262ab3882952a59312ff3537cd86d894523c5ab/engine/compressors/diff.go#L24)).

## Where rtok loses today (#1076, measured 2026-10-14)

| Case | rtok | best competitor |
|---|---:|---:|
| git status (dirty) | 0.0 % | lean-ctx 40.5 % |
| git diff HEAD~5 | 1.4 % | token-optimizer 97.9 % (51 % paths) / sqz 44.4 % (93 % paths) |
| git diff worktree | 14.7 % | token-optimizer 79.1 % (100 % paths) |
| ls -la | 0.0 % | lean-ctx 66.6 % |
| grep -rn | 81.7 % (71 % anchors) | token-optimizer 89.7 % / lean-ctx 89.6 % (100 %) |
| cat source file | 86.2 % | lean-ctx 91.4 % |
| MCP catalogs | 0 % | caveman 10–29 % |

rtok wins `git log` (#1079 keeps 89 % SHAs), `find`, `ls -laR`, `cat app.log`.

## Proposals by category (ordered by frequency; rivals measured 2026-10-14 in #1076)

Shared rules for every proposal: run only on exit 0 or keep stderr + exit line verbatim; keep
every full/abbreviated hash, every path, every line matching `WARN|ERROR|error|fatal|panic|FAIL`;
"never worse than raw" (already in #1077); archive raw and print the short `expand` id.
Also shorten the trailing link line (today ~90 tokens with two 64-hex ids) to one 8–12 hex id:
that alone is 70+ tokens on every compressed call and dominates small outputs.
The command trailer has printed that 8-character id since T672. The archive live-zone pointer
and the compress view do too (T909). A decision already stored is replayed as written.

Pointers print 8 hex chars (T672). `stats` counts a later expand of that prefix (T911).

| # | Category (uses) | Best rival | rtok design (better, not copied) | Target |
|---|---|---|---|---|
| T672 | all (link line) | sqz `§ref§` ~13 tok | one short id `[rtok 1a2b3c4d · 111 lines]`, expand by prefix | −75 tok/call |
| T673 | grep 2260, git grep 452, rg | t-o 89.7 %, lean-ctx 89.6 % | path printed once; matches as `line:text`; collapse common indentation; trim match text around the hit to ±60 chars keeping the match itself; per-file cap by match budget with `+N more (expand)`; files sorted by path (stable) not count; keep 100 % paths | ≥ 91 %, 100 % paths |
| T674 | sed 2119 (`sed -n a,bp f`), cat 950, head 35, tail 155, awk | lean-ctx 91.4 % (`read` rewrite) | detect range-print of a file: emit numbered range only once per session (session dedup by content hash, re-read → `unchanged since <id>`, changed → unified diff of the range); for full source files without range: signatures + elided bodies with line spans | ≥ 93 % on `cat` source; re-read ≥ 95 % |
| T675 | python3 1545 | none specialised | detect pytest/unittest/traceback output: keep the full last traceback, collapse repeated frames, summary line; JSON stdout → TOON (#1078) | ≥ t-o pytest (564→115) |
| T676 | gh 744 | lean-ctx gh.rs | `gh pr/issue view`: drop empty fields, body markdown whitespace collapse; `gh run view --log`: per-step errors only; `gh pr checks`: only non-pass rows + counts; `--json` → TOON | +20 pp vs current head/tail |
| T677 | mise 660 | lean-ctx mise.rs | drop install/progress lines, keep task header + failing task output; `mise run` = inner command formatter | ≥ lean-ctx |
| T678 | for 570, until 231, while | caveman repetition | loop output: normalise numbers/ids per line, collapse runs of same-shape lines into `shape ×N (vals: a..b)`; never collapse lines with error words | ≥ 80 % on polling loops |
| T679 | ls 386 | lean-ctx 66.6 % | ls -l → `name size` with dirs first, drop perms unless non-default (`x` bit, symlink target kept as `a -> b`); group by extension when > 30 entries | ≥ 72 % (= rtk) |
| T680 | git status 297 | lean-ctx 40.5 % | one line per section with paths grouped by dir (`src/plugins/cmd/{a,b}.rs`), sigils, drop git hints; branch + ahead/behind | ≥ 60 % (rtk 62.5 %) |
| T681 | git diff 272, git show 262 | t-o 97.9 % / sqz 44.4 % | two tiers: ≤ budget → drop `index`, context 1, fold unchanged runs, strip common indent; > budget → per-file `path +A -D` table and hunk headers with function names, full hunks only for the N smallest files; 100 % paths always | ≥ 80 % big diff with 100 % paths (t-o keeps 51 %) |
| T682 | git log 174 | — (rtok wins after #1079) | keep #1079; add `[N commits, +A/-D]`; drop Author when all equal | keep lead |
| T683 | tail 155, cat *.log | lean-ctx 87.4 % (rtok 94.5 %) | caveman-style field summary on collapsed runs: constant logfmt keys printed once | keep lead, +2 pp |
| T684 | wc 102, cargo test/build/clippy | rtk / sqz | wc: drop padding, keep `total`; cargo: #1077 + group warnings by lint code `unused_imports ×12 (files…)` | ≥ rtk |
| T685 | find 33 | rtok leads | keep dir grouping but print full paths for ≤ 50 hits so the anchor check passes | 100 % paths, ≥ 69 % |
| T686 | MCP tool catalogs | caveman 12–29 % | `rtok mcp` proxy: first sentence of descriptions, drop default/examples that duplicate type, enum compaction; recovery via archive | ≥ 30 % |

## sqz formatter coverage (T662)

Source: [sqz `cmd_formatters/mod.rs`](https://github.com/ojuschugh1/sqz/blob/cf2b03c8072729f366e48a8a4933a421bd2a1e79/sqz_engine/src/cmd_formatters/mod.rs#L120-L180),
checked 2026-10-15. `rtok discover` is on main (T688). This table still uses sqz's list from that date, not a live `discover` ranking.
"Saving" is est tokens on the new `tests/cmd_golden/*` fixtures (synthetic, shaped like each tool's output); "default" means
`Rule::default()` already wins and a dedicated rule would not beat it.

| sqz arm | rtok route | Saving on the golden |
| --- | --- | --- |
| git, cargo, pytest, go, docker, kubectl, tree | formatter (existing) | see `docs/cmd-rules.md` |
| npm/npx, pnpm, pip, gradle, mvn, dotnet, tsc, eslint, grep/rg, curl | `[rule]` (existing) | see `docs/cmd-rules.md` |
| ruff | **new** `[ruff]`, `group = "diag"` | 96 % (default 72 %) |
| mypy | **new** `[mypy]`, `group = "diag"` | 77 % (default 55 %) |
| rubocop | **new** `[rubocop]`, `group = "diag"` | 88 % (default 82 %) |
| golangci-lint | **new** `[golangci-lint]`, `group = "diag"` | 91 % (default 53 %) |
| yarn | default | 16 % (a rule gave the same bytes) |
| bun | default | 65 % |
| rspec | default | 71 % |
| terraform / tf | default | 74 % |
| xcodebuild | default | 90 % |
| biome, rake/rails, bundle, wget, logcat | default, no golden | not measured |
| podman | `docker ps` formatter also matches `podman ps` (T905) | same golden as `docker ps` |

What made the four new rows win is not a rule but a parser: `parse_diag` now reads `path:line[:col]: CODE msg`
(ruff, flake8), `path:line: error: msg  [code]` (mypy), `path:line:col: C: Cop/Name: msg` (rubocop) and
`path:line:col: msg (linter)` (golangci-lint), so the existing diagnostic grouping folds them to `CODE ×N: msg (first locations…)`.
The full list stays behind `expand <id>`.

## Correctness checks for every task
- Anchor check from #1076 (`bench/c14`): 100 % of hashes (7-char prefix), paths and WARN/ERROR lines.
- Exit status and stderr unchanged on failure.
- Output never has more tokens than raw (o200k_base).
