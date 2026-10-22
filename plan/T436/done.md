Creator request 2026-10-07: a spinner on every wait and ketch-style operation icons in the rtok CLI. Split on closing: the remaining waits and `agents install/update` icons are T436.2, the crate shared with ketch is T436.3.

Check: `src/ui/style.rs` unit tests (the icon of each verb, the `Kind` fallback, one width and one text column for every icon, bare text off a terminal); `tests/ui_style.rs` asserts no tone or operation icon reaches a pipe; `src/render.rs` loader test; CI `gate` green.

Result: `style.rs` has a verb → icon table on ketch's model (ketch's rows plus index, worktree, compress, expand, bench, start, stop), substring-matched in order so `uninstall` wins over `install`; a line that names an operation takes its icon, any other its `Kind` icon. Warn is now the single wide code point ❗ (as in ketch) instead of ⚠️, whose width depends on U+FE0F. Icons pad to a measured 2-column gutter. The daemon start/stop lines, the `graph index` summary and the references line go through it. The CLI's loader became the public `render::with_loader` and also wraps `bench` and `worktree add/remove`; it draws nothing off a terminal, so hook, MCP, `--json` and piped output and the trycmd snapshots are unchanged. `unicode-width` (already in the tree through indicatif) became a direct dependency.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
