Scope: the T358.4 bullet under "Split when claiming" in T358.

Check: one fixture per host pins its totals; `unsupported` hosts are listed in `docs/agents.md`; `just check`.

Execution (formats from primary sources only, cited in `research.md` 30.5 to 30.9): (1) `[agents.usage.dirs]` gains `pi`, `kimi`, `grok`, `zcode`, `antigravity`; an untouched default follows `PI_CODING_AGENT_SESSION_DIR` (else `PI_CODING_AGENT_DIR/sessions`), `KIMI_CODE_HOME` and `GROK_HOME`. (2) pi: the `usage` object of `message`, `compaction`, `branch_summary` and `usage` entries, one count per (`id`, `timestamp`). (3) Kimi Code: `usage.record` lines of every `agents/*/wire.jsonl`. (4) Grok, ZCode and Antigravity are `unsupported`: no primary source documents the token fields, and xAI documents `grok usage` (which rtok does not run) instead of the files. (5) Fixtures in unit tests and `tests/trycmd`; each supported reader run once, read-only, on this machine's real files and compared with an independent `jq` sum.

Result (2026-10-03, Claude Code / sonnet-5): two readers, `src/measure/usage/pi.rs` and `kimi.rs`, and `unsupported` entries in `skipped` for Droid, Grok, ZCode and Antigravity when their directory has content (one reason constant per host). Real-file runs matched an independent `jq` sum on all four legs: pi 953 entries (input 8,063,051, cache read 129,106,459, output 744,047) and Kimi Code 360 records (input 3,398,492, cache read 33,477,393, output 250,065); no content was copied. Not done: Grok (reading `signals.json` needs the creator to accept observed keys as a source), ZCode and Antigravity (undocumented), and the legacy `~/.kimi` of the archived kimi-cli.

Status: done 2026-10-03
Model: Claude Code / claude-sonnet-5
