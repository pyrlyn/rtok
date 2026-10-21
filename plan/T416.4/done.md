`worktree remove` (the worktree path, its branch and its size and file count), `completions install` and `completions uninstall` (the file diff), `graph projects remove` and `graph projects unlink` (the rows that would go). Out of scope, because they are easy to undo or change no files: `memory pin/unpin/retire/revise`, `agents send/status`, `graph projects add/select/link`, `demon *`, `otel flush`.

Check: a test per command that the dry run changes nothing and prints the preview; `just check` green.

Result: `worktree remove --dry-run` (path, size, files, branch fate; every refusal still applies; `--json` gains `bytes`/`files`), `completions <shell> --install|--uninstall --dry-run` (file diff), `graph projects remove --dry-run` (root, index rows, indexed files, link counts) and `graph projects unlink --dry-run`. Tests in `tests/worktree.rs`, `tests/completions.rs`, `tests/graph_projects.rs` plus five trycmd cases.
