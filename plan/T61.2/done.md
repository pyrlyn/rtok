From the graymatter gap review (`research.md` §14). graymatter's `context-sync` projects the highest-weight facts into a marker-fenced block in `CLAUDE.md` / `AGENTS.md` within an explicit token budget, detects hand edits inside the block, backs the file up and never writes outside the markers. Every host reads those files natively — including the hosts whose `support()` row has no SessionStart injection (`docs/agents.md`) — and the block sits in the cached prefix at the same price as a hook injection. Risk: on a host where hook recall is on, the same titles are paid twice (the T59.7 overlap class).
Done when:
1. `rtok memory sync [--file CLAUDE.md|AGENTS.md] [--budget N] [--dry-run] [--remove]` writes pinned notes first, then remaining live notes by id desc (T69.2 closed without ranking), as `id title` lines between `<!-- rtok:memory -->` / `<!-- /rtok:memory -->`, ≤ `[plugins.memory] sync_tokens` (default 300), byte-stable for an unchanged store (no timestamps); creates the block at the end of the file when absent; backs the file up through `rtok_agent_sdk::backup` (one helper, no copy); never changes a byte outside the markers; `--remove` deletes the block and nothing else.
2. Hand-edit guard: the sha256 of the last written block is kept in the store; a block whose bytes differ is refused with a message and exit 1 unless `--force`; `--dry-run` prints the unified diff.
3. Not automatic: no hook writes a file (fail-open rule). `doctor` (the T59.7 list) prints the overlap when a synced block exists and hook recall is on for the host; `sync` prints the same line.
4. `Vfs` tests: create, update, hand-edit refusal, `--remove`, outside bytes identical, budget trim; a trycmd golden; `docs/config.md` row; the memory page.

**Result.** `rtok memory sync` writes a managed marker block. Default `sync_tokens = 300`. SessionStart still only injects recall — it does not write files. T69.2 order is pinned first, then remaining live notes by id desc.

**Check:** `plugins::memory::sync` Vfs tests, doctor overlap, `config_coverage`, `surface_parity`, `cli_trycmd` (memory-sync --help). `just check` fmt-check/lint stay red on pre-existing HEAD rustfmt/clippy outside this task.

---
