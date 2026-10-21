**T23.4 the ten plugins move** · T23.2, T23.3 · `src/plugins/*/`
Do: mechanical, one commit per group of plugins if it does not fit — `measure` `cmd` `read`
`archive` `proxy`, then `inject` `guard` `memory` `graph` `toon`. Imports come from
`rtok_plugin_sdk`; behaviour does not change. Each plugin's `AGENTS.md` gets the one line that
says its contract now lives in the SDK. Exempt from the ≤ 3 files rule: it is import churn across
ten directories, and splitting it further would leave the tree half-migrated between commits.
Check: `just check` green; `rtok stats --json` over a fixture store is byte-identical before and
after; no `use crate::plugin::` remains under `src/plugins/`.
Complexity: 3/5
Status: done 2026-09-09
Model: Claude Opus 5 (anthropic/claude-opus-5)
Check result: `grep -rn 'use crate::plugin::\|use crate::proxy::wire::\|use crate::tokens::'
src/plugins/` returns nothing — every contract type now arrives as `rtok_plugin_sdk::…`, and the
three `use` lines each plugin used to carry collapsed into one. `just check` green (247 tests
across the workspace; the two `plugins::graph::watch` tests that fail under full-suite load pass
on their own and on the re-run — pre-existing flake, unrelated). `rtok stats --json` byte-identical
before and after: the modified tree's binary built a fixture store under `RTOK_HOME` with three
`rtok run` calls, the store was snapshotted, `src/plugins/` was stashed, the pre-refactor binary
read the restored snapshot, and `cmp` on the two outputs is silent (same md5).
Deviation: one commit, not two groups — the import swap compiles as a whole and splitting it would
have left half the tree on the old path. 30 files (20 sources + 10 `AGENTS.md`), under the
exemption the task already carries.
Note: `Runtime` stays `crate::plugin::Runtime`, spelled out at its three call sites (`cmd/run.rs`,
`memory/import.rs`, and the test helpers). It is rtok's host implementation, not part of the
published contract, so importing it from the SDK would have been a lie.
Note: the fixture's transcripts dir was empty, so the compared `stats` report is all zeros over a
populated `rtok.db`; the archive write path ran, the transcript scan had nothing to scan. A real
transcripts dir is not reproducible — this session writes to it while the test runs.
