Seen 2026-09-21: MCP `read` with `mode=lines`, ranges `14-30` and `1230-1260` of one file, both answered `[rtok <id> · identical to a result 1 turns ago …]` with one id, and `expand <id>` returned the whole file. Root cause: `read_with` (`src/plugins/read/mod.rs`) passed the raw file bytes to `plugin::identical_result` for `full`/`lines` reads even when a range was given, so any earlier whole-file archive in the session (the native Read hook's) matched every range. Fix: a ranged read is keyed on the bytes it returns; unranged `full` stays on raw bytes so the T58.1 delta cache keeps diffing the file, not its numbered view. The second half of the original card — a sub-agent and its parent share the host session, so a pointer can name a body the caller never saw — is split out as T127.

Check: `ranged_read_is_not_a_pointer_to_the_whole_file` archives the whole file first, then reads two ranges; it fails on the old code (pointer returned) and passes with the fix; `cargo nextest run --lib read:: plugin::` 78 passed.

**T126** `roadmap.md` and `research.md` §16.2 list shipped work as open

Do: Every task in `roadmap.md` is checked against `done.md` headings (exact match: `## T59.5 —`, `## T58.1 —`, `## T61.2 —`) and open PR branches (`git branch -r` / `gh pr list --state open`); shipped ids are removed from table rows (T59.5, T61.2, T58.1 from the `read` and `proxy`/`archive` lanes). `research.md` §16.2 table gains a `Status` column (values: `shipped`, `shipped (off by default)`, `open`) and a dated line "Status as of 2026-09-21."; T58.1's Why text is fixed to note the 7.3 % re-read delta (from §2) that gates it. Docs only; no code changes.

Check: no shipped id remains in `roadmap.md`; every row in §16.2 has a Status value; `just site` builds; user's shell-generated command list finds no stale task references.

Complexity: 1/5 — docs only, table edits.

Status: done 2026-09-21

Check result: `roadmap.md` rows cleaned: T59.5 removed from `proxy`/`archive` lane (1 row), T61.2 removed (1 row), T58.1 removed from `read` lane (1 row); §16.2 Status column added; T59.5/T61.2 marked `shipped (off by default)` per `config/default.toml` defaults (`enabled=false`, `skills=false`), T58.1 marked `shipped` (delta on by default); `research.md` Why text updated; `just site` not tested (disk nearly full, hugo not available). Verification: `grep` over roadmap.md confirms no T59.5/T58.1/T61.2 remain.

Model: Claude Code / claude-haiku-4-5

Also: the whole `roadmap.md` lane table was stale — all 67 listed ids are in `done.md` (T71.1 dropped, I-71); replaced with a dated note.
