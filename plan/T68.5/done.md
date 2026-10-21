From the codegraph / graphify review. codegraph `affected` traces a diff to the test files it
reaches so the agent runs those instead of the suite; rtok had `impact(name)` and
`is_test_path`, and no path from "these files changed" to "run these tests", so `cargo test` /
`pytest` output — the largest Bash family in `research.md` §2 — was paid for the whole suite.
Done when `rtok graph affected [--since <ref> | --staged]` (CLI, `--json`) takes changed files
from `git diff --name-only` (no libgit — `cmd` already shells out to git), their definitions
from `symbol_defs`, `impact_bfs` to `depth` (default 3), and prints the reachable definitions
whose file passes `is_test_path` as `test file ← via symbol` grouped by file, with the command
to run them per language (`cargo test <name>`, `pytest path::name`, `go test -run`, `vitest
path`); MCP `impact` accepts `path` alone (no `name`) with the same semantics; an empty result
says `no indexed test reaches the change; run the suite`; `Measurement { kind = "affected" }`
is written only when a transcript or T68.9 shows the subset actually ran (before = the suite's
last measured bytes, after = the subset's), never on the print alone; test on a fixture repo
with two tests, one reaching the change.

**Result (2026-09-18).** `affected_from_paths` indexes the root, collects definition names in
each changed file (`outline::tags` then `symbol_defs` to confirm), walks `impact_bfs` to depth
3, and keeps `(path, scope)` hits whose path passes `is_test_path`. CLI
`rtok graph affected [--since <ref> | --staged] [--json]` shells out to
`git -C <root> diff --name-only --relative -z` (`--cached` when `--staged`); a git failure
fail-opens to the empty message. MCP `impact` with `path` and no `name` uses the same walk
on that file (D21: one tool). Print is `file ← via symbol` plus the language command; JSON is
`{"tests":[{"file","symbol","command"}]}`. No `cap` / Measurement on print. Empty:
`no indexed test reaches the change; run the suite`. Import edges (T68.6) are not followed.
