From the Empryo study (idea-only, clean-room). `Checkpoint` recorded paths without what happened to them, so after compact the agent re-read files it only looked at and could miss the ones it changed. The checkpoint now lists every file with its action, `path <p> (edited|created|deleted|read[ a-b])`, changed files first (edited, created, deleted, then read, each group by path, byte-stable). Actions are folded from the transcript's `tool_use` blocks: Read is read (with its offset/limit range), Edit/MultiEdit is edited, Write is edited unless its result says the file was created, Bash `rm` / `git rm` is deleted (shlex; globs, variables, `~`, `--cached`, `-n` and relative paths after a `cd` are skipped). Rows written before the change decode as reads; `last_paths` drops deleted files from the repo-map personalization. No schema change: the checkpoint is note text.

Check: unit tests for the event to action mapping and old-row decode; the checkpoint rendering snapshot (`insta`); `just check`.
Result: after a session that reads A and edits B the checkpoint lists `B (edited)` before `A (read)`; `cargo test --lib checkpoint` 30 passed; `just check` 433 passed. #833.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
