# Agent notes — `guard`

**Owns** `src/plugins/guard/**`.

**Contract**: the `Plugin` trait, `Ctx` and the host capabilities come from the published
`rtok-plugin-sdk` crate (`crates/rtok-plugin-sdk`), not from `crate::plugin` — import them as
`rtok_plugin_sdk::…`.

**Invariants**
- Deny only when the prior result is retrievable (an `archive` row exists); otherwise stay silent.
- Normalise before comparing (trim, collapse whitespace, strip the `rtok run` wrap) so
  trivially different commands still match, but never match different file paths. A Bash
  key carries the hook's cwd and every leading `cd … &&` hop verbatim (hops are relative
  to the persistent shell, so they never fold, T445); a Bash behind a relative hop
  is never keyed (Post already sees the moved cwd), and any `cd` hop clears all other
  `bash` keys because it moves the shell.
- Runs on the hook path: one indexed DB lookup plus a file-existence stat, never a
  body read (`archive_size`, T55.16).
- Each denial writes `Measurement { kind: "guard" }` with the avoided result size.
- `skill.rs` (T62.1, opt-in `skills = true`) is the one path that reads a file on the
  hook: `SKILL.md` of the named skill, resolved from the name only (no separators, no
  `..`); over `skill_max_bytes` it archives the body and denies with the heading map
  (`kind: "skill"`), never when the frontmatter carries `allowed-tools`, `model`,
  `context` or `agent`. The whole reason stays under the cap.
  T392, always on: `PostToolUse(Skill)` records each load in the read cache (`skill\t<name>`,
  per context window) and adds one line when the skill loads again before a compaction;
  `PreCompact` and `SessionStart` `compact` clear the keys. Added context, never a deny, and
  no `Measurement`: it claims no saving. Hosts without PostToolUse context stay silent.
- `grep_symbol.rs` (T369, opt-in `grep_symbol = true`, cfg `graph`): index lookup only
  (`symbol_defs`, never `index_for`), 1-5 definitions, each re-checked against its file's
  line (a stale row falls through), text from `graph::def_text`, capped at the inject
  budget; `kind: "grep_symbol"` Measurement carries no saving (D3).

**Checks**: `plan.md` T2.6. Order: `roadmap.md` § `guard`.
