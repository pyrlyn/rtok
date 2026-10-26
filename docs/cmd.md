# Cmd plugin

Port of `src/plugins/cmd` (10k lines, 207 Rust tests). Code: `rtok/src/nativeMain/kotlin/rtok/plugins/cmd/`.
Every file is ported except `catfile.rs` (see Limitations).

## How it works
1. **Rewrite** (`Hook.kt`, `CmdPlugin.preTool`): a Bash `PreToolUse` command becomes
   `<leading cd hops> <run guard> rtok run [--agent <id>] -- '<cmd>'`. It is left alone for:
   `never_wrap` stems (`rtok`, `sudo`), heredocs, background `&`, interactive `-i`/`--interactive`
   (per-stem table), commands that change shell state (`cd`, `export`, `source`, bare `A=1`, …), and
   any git command from a sub-agent. The run guard falls back to running the command itself when `rtok`
   is not on `PATH`.
2. **Run** (`Run.kt`): `rtok run` runs the command in the configured shell (config, then a POSIX
   `$SHELL`, then `/bin/sh`), with stderr merged into stdout. Capture stops at 32 MiB and a notice is
   appended. The command's exit code is kept.
3. **Filter** (`Formatters.compress`): dedicated formatters (cargo, git, gh, pytest, python tracebacks,
   jest/vitest, go test, tree, docker/podman/kubectl/ps/df/sqlite3 tables, wc, mise, shell loops) or the
   TOML rule engine (`Rules.kt`, builtin `rules/default.toml` plus `~/.rtok/rules.toml` and `rules.d/`).
   Wrappers (`mise exec`, `just`, `uv run`, env assignments) are peeled when the inner command is
   visible. A mixed chain uses the `[script]` rule.
4. **Emit** (`emitFilteredTo`): output that is identical to an earlier one in the same agent window becomes
   a dedup pointer. A bounded read, a body that is tiny or not shorter, and `rtok expand` output print raw.
   Otherwise the original is archived and `[rtok <id> · N lines]` is appended when anything was dropped.
   Each run records one `cmd` measurement row, estimated on the host-visible prefix (30 000 chars).
5. **`rtok filter <cmd>`** (`Filter.kt`) does the same for text on stdin.

## Enable / disable
- `[plugins.cmd] enabled = false` turns the plugin off; `rewrite = false` keeps `rtok run` but stops the
  PreToolUse rewrite.
- `trailer`, `trailer_min_lines`, `fail_tail_lines`, `shell`, `rules`, `rules_dir`, `never_wrap`,
  `interactive_stems` are the same keys as in Rust.
- `rtok config validate` checks the rule files (wired in `main` through `Rules.installValidateCheck`).

## Test
`./gradlew --no-daemon :rtok:macosArm64Test --tests 'rtok.plugins.cmd.*'`. The golden tests read
`rtok/src/commonTest/fixtures/cmd_golden/*.in|.out`, a copy of Rust `tests/cmd_golden`.

## Limitations
- `catfile.rs` is not ported: it needs tree-sitter outline tags (tree-sitter is not an approved dependency)
  and the read plugin's cache. So `cat <code file>` gets the `[cat]` rule instead of an outline, and the
  re-read delta for `cat` (Rust `catfile::reread_key`) is skipped.
- Windows-only paths (cmd.exe raw args, PowerShell runs) are ported as pure functions only; no Windows
  target is built.
- The spinner is a simple stderr spinner drawn from the capture loop; Rust's indicatif progress bars for
  parsed progress output are not ported.
