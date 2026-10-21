From I-39. `skip_wrap` treats any `-i` / `--interactive` token as interactive, so `ffmpeg -i in.mp4`, `curl -i`, `ssh -i key` are never wrapped: their output is neither archived nor filtered, and for `ffmpeg` and `curl` that is most of the family's bytes. A wrong "non-interactive" verdict wraps a prompt-waiting command and hangs the tool call, so the table is per stem, not per flag.
Done when:
1. Evidence: count of unwrapped Bash calls by stem and bytes (`stats` over transcripts, the T57.1 path) in `research.md`; stems whose `-i` is a real REPL flag (`python`, `node`, `psql`, `sqlite3`, `irb`, `bash`, `sh`, `zsh`, `docker exec/run`, `kubectl exec`) stay interactive.
2. `skip_wrap` consults the stem first: `-i` means interactive only for the REPL stems above and `--interactive` anywhere; every other stem is wrapped. Table lives next to `never_wrap` and is overridable in config.
3. Unit tests: `ffmpeg -i x` wrapped, `ssh -i key host` wrapped, `python -i` skipped, `docker run -i` skipped, `--interactive` always skipped; hook e2e: `curl -i` produces a `Measurement`.

**Result (2026-09-18).** Commit `45c4531`. Per-stem interactive table in `src/plugins/cmd/hook.rs`; `-i` interactive only for REPL stems; unit and hook e2e tests.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
