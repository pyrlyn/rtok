**T55.7 Stats `strip_prefix_cd` and quoted paths** · P3, 1/5 · `src/measure/stats.rs`

From review 2026-09-17. `strip_prefix_cd` split the path on the first whitespace, so `cd 'My Documents' && git status` / `cd "C:\Program Files\…" && …` did not strip and `bash_family` bucketed the call as `cd`.

Do: one `skip_word` helper (bare word or `'…'` / `"…"` segments, `~/'My Documents'/src` mixed; unterminated quote → `None` so the caller fails open and the command stays untouched). `strip_prefix_env` and `strip_prefix_cd` both use it, replacing the two ad-hoc splitters. `guard::strip_cd_and` is untouched (its cwd-blind key is T55.9).

Check: `bash_family_strips_quoted_cd_paths` — spaced single/double-quoted paths, mixed quoting, quoted env before `cd`, tab separators, unterminated quote stays `cd`, `cdx` is not `cd`; `bash_family_strips_cd_and_env` unchanged.
Status: done 2026-09-17 · Model: Claude Code / Fable 5.1
Evidence: `just check` green (fmt, clippy `-D warnings`, nextest workspace, build-min, jscpd).
Deviation: none. Review of neighbouring code filed T55.8 (guard `read:` keys survive a mutating Bash), T55.9 (guard Bash key cwd-blind), T55.10 (three copies of `cmd_stem`) and idea I-38.
