# Guard plugin

Port of `src/plugins/guard` (`mod.rs`, `sed.rs`, `skill.rs`). Code: `rtok/src/nativeMain/kotlin/rtok/plugins/guard/`.

## How it works
- **Duplicate deny.** `postTool` archives each Read/Bash result (≤ 256 KiB, `ARCHIVE_CAP_BYTES`) and stores a
  read-cache key → archive id. A repeat inside `window_turns` calls is denied in `preTool` with
  `duplicate; rtok expand <id8>` and a `guard` measurement (`before_bytes` = archive size, `est_before` = bytes/4).
  The deny reads archive metadata only and fails open when the archive is missing.
- **Keys.** Read: `read\t<path>[\t<offset>:<limit>:<pages>]`, the path resolved lexically against the hook cwd
  (`..` or a relative path without cwd → no key). Bash: `bash\t[<cwd>\t]<command>` for read-only commands only
  (ls, cat, head, tail, grep, rg, find, tree, wc, jq, print-only sed, awk without writers, listing git forms,
  `cargo metadata`); whitespace is normalised, the `rtok run --` wrap and the POSIX run guard are stripped,
  absolute `cd … &&` hops are kept verbatim, relative hops are never keyed. A sub-agent's key carries its id.
- **Invalidation.** A mutating Bash clears every `bash` and `read` key; Edit/Write clear `bash` keys and that
  file's `read\t<path>` keys (all reads when the path has no identity); a `cd` hop clears `bash` keys.
- **Native Grep/Glob redirect (T50.4)**, opt-in: denies pointing at MCP `search`/`tree` while `read` is on.
- **Skills (T62.1, T392).** With `skills = true`, a `SKILL.md` over `skill_max_bytes` is archived and the deny
  reason carries its heading map (`rtok.plugins.read.markdownDigest`) and the expand pointer. Skills whose
  frontmatter names `allowed-tools`, `model`, `context` or `agent` always load whole. A repeat load before a
  compaction gets a one-line note; `PreCompact` / `SessionStart(compact)` forget the loads.
- `check(tool, rawInput, host)` is the body of `rtok guard check --tool T --json INPUT [--session S] [--host H]`
  (`cli/GuardCmd.kt`; prints JSON `{"allow":…,"reason":…}`).

## Enable / disable
On by default. `[plugins.guard] enabled = false` turns it off; `window_turns` (8), `deny_grep_glob` (false),
`skills` (false), `skill_max_bytes` (8192).

## Test
`./gradlew :rtok:macosArm64Test` — `GuardTest` (35 ported + 1 new `check` test), `SkillTest` (10), plus the
`types.rs` Copilot `view` → guard key assertion in `TypesTest`. `GuardCheckE2eSpec` (`./gradlew e2eTest`) is tests/guard_check.rs against the binary: allow, repeat deny with a guard measurement, `find -delete`, `sed -i`; `GuardCliSpec` (`./gradlew cliTest`) keeps the in-process cases (bad JSON, usage errors).

## Limitations
- `grep_symbol` (T369, answering symbol-shaped Grep from the graph) is not ported: it needs the graph plugin
  (tree-sitter, pending approval). Its 8 Rust tests are pending.
- The frontmatter check uses a small own parser (`frontmatterKeys`) instead of Rust's `serde_saphyr`: block
  mappings with plain/quoted keys and single-line flow mappings. Anything it cannot read counts as exempt
  (fail open, as in Rust). No new dependency.
- The `rtok guard check` CLI command itself is wired with the remaining Phase 10 CLI commands.
- Windows paths (drive letters) are recognised in `cd` hops only; path identity is POSIX.
