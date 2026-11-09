# T8.1. Done

PR: https://github.com/pyrlyn/rtok/pull/279

## What was done

- **Board adapter.** `BoardAdapter` stores each task as `<dir>/{todo,in-progress,done,closed}/<ID>-<slug>.md`.
  - The folder is the status. The front matter holds `id`, `title`, `created` and `updated` (ISO 8601), plus optional fields.
  - The closing report goes under `## Report` and is written in the same atomic write as the move.
  - Writes take `<dir>/.lock`.
  - A `.gitignore` is written once, the first time rtok writes to the folder.
  - Keys the board does not know are kept on rewrite.
- **Config.** `[tasks] dir`, `layout` (`auto | board | files | plan`) and `shared` (default true: one board under the main checkout).
  - `dir` is checked to stay inside the repository.
  - The old `[tasks.disk]` keys are read when the new ones are left at their defaults.
- **Commands.** `rtok task board [--all] [--json]` and `rtok task migrate [--dry-run] [--json]`.
- **Status rename.** The `open` status is now `todo`. Old words still parse.
- **Read-only commands.** `list`, `show`, `ready`, `next` and `board` take no lock and create nothing.
- **Warnings.** Unparseable task files warn on stderr by default, and the legacy layout prints a hint to run `migrate`.

## Deviations

- `BoardAdapter` still imports `rtok.store.TaskId` and `writeAtomic`. T8.8 moves it into the `kotlin-taskboard` files module.
- `board` shows the 10 newest done and closed tasks unless `--all` is passed.

## How it was checked

- `./gradlew macosArm64Test cliTest e2eTest`, rebased on main, passes. e2e passes 65 of 65.
  - The new tests are `BoardTest`, `MigrateTest` and `BoardRootTest`, plus the board and migrate case in `TaskE2eSpec`.
- `migrate --dry-run`, then a real `migrate`, on a copy of a live `tasks/` folder with 4 tasks.
  - It moved R1 and R2 to `todo/` and renamed R3 and R4 within `done/`.
  - A second run printed "nothing to migrate".
