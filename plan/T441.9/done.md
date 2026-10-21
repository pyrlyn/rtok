Ninth subtask of T441 (task adapters): the user-facing page for tasks, in English with `docs/ru` and `docs/uk` twins.

Check: `cargo test --test agents_doc --test host_docs --test docs_structure` green; CI `gate` green.

Result: `docs/tasks.md`, `docs/ru/tasks.md` and `docs/uk/tasks.md` cover ids and the per-project counter, statuses and their aliases, the disk adapter's file layout (task files belong to the checkout they are written in; only the numbering is shared through the store), the `rtok task` commands, the MCP `task_*` tools and the `[tasks]` keys; README links the page. The GitHub and GitLab adapters are marked as not built yet: T441.7 and T441.8 add their own sections to the page.

Status: done 2026-10-07
Model: Claude Code / claude-haiku-4-5
