An upsert on `(project, kind, title)` replaces the body. The previous title and body go into `note_versions` before that write, in the same immediate transaction, with `version = COALESCE(MAX(version), 0) + 1`. Migration `0035_note_versions` (`0034` is already used by open pull requests). Kinds `checkpoint:*` and `session:*` write no version rows; a same-body upsert writes none. `rtok memory history <id>` prints the rows oldest first. SessionStart recall and `mem_get` stay on the current body. No MCP tool. Private-tag redaction is a separate change and is not part of this task.

Check: `cargo test --lib plugins::memory::`; `cargo test --lib store::`; `cargo test --test memory_status`; `just check`.

Result: `upsert_note` records the previous title and body in `note_versions` inside one immediate transaction. `rtok memory history <id>` prints those rows oldest first. Checkpoint and session kinds, and a same-body upsert, write no version row. Recall and `mem_get` stay on the current body. `just check`: 2972 passed, 6 skipped.

Status: done 2026-10-08
Model: Cursor / grok 4.7
