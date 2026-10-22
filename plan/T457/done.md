`strip_wrap` only removed `rtok run -- `, so a sub-agent rewrite (`rtok run --agent <id> -- '…'`) still had stem `rtok`. `cache_key` was `None`, and PostToolUse treated that read-only command as a mutation and cleared every `bash` and `read` guard key.

`strip_run_wrap` also drops `rtok run --agent <id> -- ` when `<id>` is 1–64 bytes of ASCII alnum, `_` or `-` (the token `cmd` embeds). A lookalike is left intact. A writer behind the same wrap still clears.

Check: `agent_wrap_matches_the_inner_command_and_keeps_other_keys` and the `--agent` asserts in `bash_key_keeps_every_absolute_cd_hop_and_the_cwd`; `just check`.

Result: `just check` against `61d491aa`: fmt, clippy `-D warnings`, `build-min`, jscpd 1.84% (under 2%), test-changed 506 passed.
Status: done 2026-10-09
Model: Cursor / grok 4.7
