**T3.4 PreToolUse(Bash) rewrite** · T2.1, T3.1 · `src/plugins/cmd/hook.rs`
Do: return `updatedInput.command = "rtok run -- " + original` unless: first word is in `plugins.cmd.never_wrap` (default `rtok`, `sudo`), contains heredoc `<<`, `&` background, `-i`/`--interactive`, or config `plugins.cmd.rewrite = false`. Emit `permissionDecisionReason` "wrapped by rtok".
Check: fixture with `git status` → wrapped; fixture with `cat <<EOF` → untouched; fixture with `sudo ls` → untouched.
Status: done 2026-09-02 · Check: `cargo test cmd::hook` — `git status` wraps; heredoc and `sudo ls` skip.
