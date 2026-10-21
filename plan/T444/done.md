`skip_wrap` in `src/plugins/cmd/hook.rs` took the stem from the first word of the whole command (`cd`), and after the `strip_cd_hop` loop in `pre_tool` nothing re-checked the remainder. `cd /tmp && rtok expand abc` was rewritten to `cd /tmp && rtok run -- 'rtok expand abc'`, so expand output was compressed again and broke the lossless contract; `cd /tmp && sudo ls` was wrapped although `never_wrap` lists `rtok` and `sudo`; `cd x && python -i` likewise. Second issue: `bounded::lex` ignored backslash escapes, so `echo it\'s && cd sub` hid the `cd` from `changes_shell_state` and the command was wrapped (the `cd` lost in the child shell). `changes_shell_state` also missed `set`, `shopt`, `umask`, `trap`, `declare`, `typeset`, `readonly` and a bare `NAME=value` stage.

Done means: skip rules run on the post-hop remainder too; the lexer honours `\` outside quotes (next char literal, `\`+newline stays a line continuation) and inside double quotes (`\"`, `\\`, `\$`, `` \` `` escape; single quotes have no escapes); the builtin list grows; each fix has a unit test.

Result: `pre_tool` calls `skip_wrap` on the remainder after the hop loop; `bounded::lex` gained the escape rules; `changes_shell_state` lists the new builtins and `is_bare_assignment` (`A=1` alone, not `A=1 cargo test`). Tests: `skip_rules_apply_behind_cd_hops`, `escaped_quote_does_not_hide_a_cd`, `more_state_builtins_and_bare_assignments_stay_unwrapped`, `lexer_backslash_escapes`.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
