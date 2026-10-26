# archive plugin

Port of `src/plugins/archive` (`mod.rs`, `pi.rs`) and `src/cli/archive.rs`.

## How it works
Tool results older than `keep_turns` user turns and larger than `min_tokens` become
`[archived <id8>: N lines · T tokens · expand(<id8>)]` plus head/tail lines (each clipped to 200 chars).
The original goes to the archive store; the decision is persisted per `tool_use_id` (per session), so
every replay is byte-identical and an expanded id is never rewritten again. Decisions written by
`toon` / `json_tree` are left alone. Options: `tiers` (L0 pointer line / L1 extract at the hot boundary),
`live_blobs` (JSON dumps, `data:` URIs, base64 runs in user text, from turn 2), `skills` (skill bodies).
All rewritten blocks of one request are nested under one `plugin_run` call with before/after token rows.

Carriers: the proxy (`proxy.mode = "compress"`, Phase 7) and `rtok archive rewrite --stdin` — the pi
`context` event: message array on stdin, rewritten array on stdout; input that is not an array or has
nothing eligible is echoed byte for byte.

## Enable / disable
```toml
[plugins.archive]
enabled = true      # false: `archive rewrite` echoes its input
keep_turns = 4
min_tokens = 1500
head_lines = 8
tail_lines = 4
tiers = false
live_blobs = false
skills = false
```

## Test
- `./gradlew :rtok:macosArm64Test` — `ArchiveTest` (16), `PiTest` (4), Kotest `ArchiveCliSpec` (5).
- `./gradlew e2eTest` — `ArchiveE2eSpec` (3): the built binary over stdin, replay stability, files in `RTOK_HOME`.

## Limitations
- Wire fixtures tests (Anthropic / OpenAI chat / responses, image blocks) wait for the Phase 7 wires;
  the P33 CTT gate waits for `measure::jsonl` (Phase 6 stats).
