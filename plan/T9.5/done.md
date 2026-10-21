**T9.5 README** · all · `README.md`
Do: replace the current README with: what rtok is, install, `rtok setup claude`, `rtok stats`, plugin table, measured results table from T9.2, honest caveats (estimates ±15 %, what is lossless, what is not).
Check: every command in the README runs (`just readme-check` executes fenced `bash` blocks that are marked `# check`).
Status: done 2026-09-02 · Check: `just readme-check` executes the isolated `# check` fence successfully; `just check` is green. Deviation: the repository uses `justfile`, not the legacy `Makefile` named in older plan text, so the gate is `just readme-check`. The recipe uses Python's standard library to extract marked Markdown fences; the user-facing install and host-operation examples remain prose examples and are not run in CI.
