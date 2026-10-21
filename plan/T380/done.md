Ivan, 2026-10-04: every skill rtok ships is named `rtok-<name>`; the hub skill `rtok` keeps its name (it is already rtok). A skill directory whose name starts with `rtok` is rtok's: a third ownership mark beside the `.rtok-owned` marker and the byte-for-byte copy (`SkillCopy` in `crates/rtok-agent-sdk/src/lib.rs`).

Why: an unprefixed name collides with the user's own skills. On the creator's machine `~/.claude/skills/worktrees` was a symlink to a personal `~/.agents/skills/worktrees` (the pre-T155 `wt.sh` version): rtok rightly refused to touch it, so Claude kept loading the stale skill and rtok's never landed until it was removed by hand.

Done means:

- `skills/worktrees/` → `skills/rtok-worktrees/` (frontmatter `name` too); `SKILLS` in `src/agents/skill.rs` = `["rtok", "rtok-worktrees"]`; the pi bundle (`plugins/pi/skills/`) and every reference move with it (`src/agents/pi/README.md`, `src/agents/antigravity/README.md`, `docs/agents-and-worktrees.md`, `skills/rtok`, tests in `tests/skill.rs`, `tests/pi_plugin.rs`).
- Ownership: a skill destination whose directory name starts with `rtok` counts as ours for install (overwrite) and remove. A symlink is unlinked, never followed: its target is not rtok's. The marker and byte-copy rules stay for unprefixed legacy names.
- Migration: `rtok agents install <host>` removes the old `worktrees` install only when the existing rules prove it ours (marker or byte copy); a foreign `worktrees` is left alone and reported once.
- A test guards the naming: every entry in `SKILLS` except `rtok` starts with `rtok-`.
- Not ours to change: the creator's global `AGENTS.md` names the `worktrees` skill; the creator updates it after this lands (say so in the PR).

Check: the naming test (every `SKILLS` entry except `rtok` starts with `rtok-`) and the install/remove/migration tests in `tests/skill.rs` and `tests/pi_plugin.rs` pass; an install into a scratch `$HOME` holding a legacy marked `worktrees`, a foreign `worktrees` symlink and a stray `rtok-x` directory shows the expected install, skip and remove lines; `just check`.

Plan:

1. Rename `skills/worktrees/` to `skills/rtok-worktrees/` (frontmatter `name`) and move the pi bundle plus every reference (`SKILLS`, READMEs, `docs/agents-and-worktrees.md`, `skills/rtok`, `tests/skill.rs`, `tests/pi_plugin.rs`).
2. Reuse `SkillCopy` and the existing install/remove ownership checks: a destination directory whose name starts with `rtok` is ours (overwrite on install, remove on uninstall). Unlink a symlink without following it. Marker and byte-copy stay for unprefixed names.
3. On `rtok agents install <host>`, remove a legacy `worktrees` install only when marker or byte-copy already proves it ours; report a foreign `worktrees` once and leave it.
4. Add the `SKILLS` naming test and install/remove/migration cases, then run `tests/skill.rs`, `tests/pi_plugin.rs`, a scratch-`$HOME` install, and `just check`.

Result: `SKILLS` is `["rtok", "rtok-worktrees"]`. `SkillCopy` reuses `tree_copies` (the byte-copy proof) and `OWNED_MARKER`, and `unlink_at` (the same symlink-safe remove plugins already use). A destination whose name starts with `rtok` is overwritten on install and removed on uninstall; a symlink is unlinked and its target is left. Install drops a legacy `worktrees` only when the marker or a byte copy of the current hub proves it ours, and reports a foreign one once. `plugins/pi/skills/` is already gone (T234), so there was no bundle to move; the README references moved, and `skill_src` still falls back to `plugins/pi/skills/<name>` for an older ketch archive. `just check`: 2485 passed. The creator updates the `worktrees` skill name in the global `AGENTS.md` after this lands.
