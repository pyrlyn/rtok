Found by a bug-hunt pass over `src/plugins/cmd/hook.rs`. The PreToolUse rewrite turned `cd crates/x && cargo test` into `rtok run -- 'cd crates/x && cargo test'`, so the `cd` ran in `rtok run`'s child shell. Hosts that keep the shell's cwd between Bash calls (Claude Code) lost it: the next call ran in the old directory. `export`, `source`, `unset`, `alias`, `pushd`/`popd` had the same problem.

Fix (creator's choice: hoist the `cd`): leading `cd <dir> &&` hops stay outside the wrap (`cd crates/x && rtok run -- 'cargo test'`, reusing `guard::strip_cd_hop`, quote-aware). A command with any other `cd`, `pushd`, `popd`, `export`, `source`, `.`, `unset`, `alias` or `unalias` stage (found with `bounded::stages`, the existing lexer) is not wrapped at all.

Check: new `hook::tests::leading_cd_hops_stay_outside_the_wrap` and `shell_state_builtins_are_not_wrapped`; `just check` green.

Status: done 2026-10-01
Model: Claude Code / claude-opus-5-5
