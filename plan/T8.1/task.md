---
status: in progress
priority: P1
complexity: 4
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T8.1. Board layout with column folders, shared root and migrate

The board is the default store: `<dir>/todo|in-progress|done|closed/<ID>-<slug>.md`, the
folder is the status, the front matter has no `status` key, and the board lives under the
main checkout so every worktree and the MCP server share it. The old flat layout keeps
working and `rtok task migrate` converts it. Spec: "Board layout" and "Layouts and
detection" in [docs/design/task-board.md](../../docs/design/task-board.md).

Done means: a new project's first `rtok task create` makes a board; `rtok task board` shows
the four columns (text and `--json`); `rtok task migrate` turns this repo's untracked `tasks/`
(R1 to R4) into a board; the legacy `files` and `plan` layouts pass their existing tests.

Check: `./gradlew macosArm64Test cliTest e2eTest` green; `rtok task migrate --dry-run` on a
copy of `tasks/` lists the four moves.

## Execution plan

1. `Status`: rename `Open` to `Todo` (JSON `todo`), widen `Status.parse` aliases (`in progress`,
   `doing`, `dropped`, ...). Update every `Status.Open` use and the tests that pin `open`.
2. `tasks/BoardAdapter.kt` (new), next to `DiskAdapter.kt`: column folders, `<ID>-<slug>.md`
   with Unicode slugs, front matter with ISO `created`/`updated`, kept unknown keys,
   `remote`, `## Report` body section, no-op writes skipped, case-insensitive unlink guard,
   `.lock` and a board `.gitignore`. Reuse `DiskFile.kt` YAML helpers and `ClaimLockFile.kt`.
3. Board root: `[tasks] shared` (default true) resolves `dir` under `ProjectRoot.main`;
   validate `dir` (relative, no `..`). Layout detection `auto|board|files|plan` in
   `TaskProject.diskAdapter`, decided once per command; `files` warns once to migrate.
   Config keys `[tasks] dir/layout/shared` with `[tasks.disk]` read as aliases
   (rtok-config `Sections.kt`, `default.toml`, `Validate.kt`).
4. Read-only paths (`list`, `show`, `ready`, `next`, `board`) take no write lock and create no
   files.
5. `rtok task board [--all] [--json]` and `rtok task migrate [--dry-run] [--json]` in
   `cli/TaskCmd.kt`; rendering in `TaskText.kt`.
6. Tests: `BoardTest.kt` (layout, moves, slugs, hand moves, no-op writes, case guard,
   report, unknown keys), `MigrateTest.kt`, board-root tests from a worktree, `TaskE2eSpec`
   cases for `board` and `migrate`; keep `DiskTest`/`PlanDirTest` green.
