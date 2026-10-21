**T2.3 `rtok setup claude`** · T2.1 · `src/setup/claude.rs`
Do: add hook entries to `~/.claude/settings.json` (backup to `settings.json.bak-<ts>` first): PreToolUse(Bash|Read), PostToolUse(*), UserPromptSubmit, SessionStart, PreCompact, PostCompact — each a single `rtok hook <event>` command, `timeout: 5`. Idempotent (skip if present). `--dry-run` prints the diff. `--remove` deletes rtok entries only.
Check: `rtok setup claude --dry-run` shows exactly 7 additions; run twice → second run "no changes".
Status: done 2026-09-02 · Check: `--dry-run` prints `7 additions`; apply twice → second `no changes`. `--remove` keeps foreign hooks. Deviation: 4 files (`src/setup/{mod,claude}.rs`, `cli.rs`, `lib.rs`); PreToolUse is two matcher entries (Bash, Read) so the 7-count lands.
