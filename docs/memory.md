# Memory plugin

Port of `src/plugins/memory` (Rust rtok). Notes are recalled by title without an LLM extraction step.

## How it works
- **SessionStart**: a capped title index (`<id> <title> (<n>t body)`), pinned first, then by retention score. Bodies stay out; `mem_get` fetches one.
- **UserPromptSubmit**: `remember: <text>` saves a `user` note. Otherwise prompt recall searches notes (FTS5, or the stored hybrid vectors when `[plugins.memory.embed] enabled + hybrid`), re-ranks notes linked to files the session read (RRF), adds up to two notes linked to files the prompt names, and appends up to two observation lines. An unchanged index is not injected twice.
- **PostToolUse**: one scrubbed observation per tool call (≤ 400 chars, secrets redacted by `Scrub.kt`). No measurement row (T927).
- **SubagentStart**: the spawn brief (`Handoff.kt`): files read or edited, their enclosing symbol ranges when the graph index has them, archive pointers.
- **PreCompact**: `compactContext` (title index + this session's observations).
- `mem_save` goes through the admission gate (`Admit.kt`: too short, or a trigram-Jaccard duplicate).

## CLI
`rtok memory import|export|extract|retire|forget|pin|unpin|history|revise|sync`. `sync` writes a managed block into CLAUDE.md / AGENTS.md with a per-file hand-edit guard and a `.backup_configs/` copy (`rtok-agent-sdk` Backup.kt).

## Enable / disable
`[plugins.memory] enabled` (default on). Knobs: `recall_titles`, `recall_tokens`, `prompt_recall` (0 = off), `admit`, `admit_min_chars`, `admit_similarity`, `forget_after_days`, `spawn_brief`, `handoff`, `sync_tokens`, `[plugins.memory.extract]` (off by default; runs the configured `command`).

## Test
`./gradlew :rtok:macosArm64Test --tests 'rtok.plugins.memory.*'` (126 tests).

## Limitations
- `memory status` is not ported yet (needs `model::memory_status`).
- `sync --dry-run` prints the unified diff; the `--stat` view and totals line wait for the change-preview port.
- Lines print plain (no TTY styling yet).
- The MCP tools are declared; dispatch lands with `rtok mcp`.
- The handoff test with indexed symbol ranges waits for the graph (tree-sitter) port.
