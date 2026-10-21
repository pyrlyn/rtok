From run 35576438155 (2026-09-21, after T93). The dedup path in `plugins/cmd/run.rs` counted 1 where the test expects 0 — a real behavior difference, not obviously a path/shell issue like T83.2's family. Read the dedup key construction and decide whether it hashes something platform-dependent (e.g. a path or line ending) that makes two "identical" commands look different on Windows, or the test's identical-output premise doesn't hold there. One family split out of the original T83; see T83.2 for the closing criterion.

Do (Claude Code / claude-opus-5-5, 2026-09-25): not the dedup key — the test's premise. It built its two "different commands" in POSIX shell (`printf` with an 80-line argument, then `sh -c "printf '%s\n' '…'"`), and on Windows `rtok run` goes through cmd.exe, where a newline ends the command line, so the two outputs differed and nothing deduped. The test now writes the payload to two files and prints each with the resolved shell's own file printer (`type` under cmd.exe, `cat` elsewhere): different argv, identical bytes, still through `run`. Its line left the `cfg(windows)` filter in `.config/nextest.toml`.

Check: the test passes in the `windows` CI job; `just check` stays green.

Check result: `just check` green on macOS (1772 passed); PR merged only with a green `windows` job.

Status: done 2026-09-25
Model: Claude Code / claude-opus-5-5
