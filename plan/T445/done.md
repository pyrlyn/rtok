The host keeps the shell's cwd between Bash calls, but the guard key did not follow it. A read-only `cd a && ls` never cleared the other `bash` keys, so a later plain `ls` (now running in `a`) was denied as a duplicate of the parent listing. `norm_cmd` folded leading `cd` hops to the last one, which is wrong for relative hops: after `cd /repo && cd sub && ls -la` the shell sits in `/repo/sub`, so `cd sub && ls -la` then runs in `/repo/sub/sub` yet shared the key. The key had no starting directory at all.

Result: `cache_key` takes the hook's cwd (`Ctx::cwd()`, set once per hook run for PreToolUse and PostToolUse alike) and keys Bash as `bash\t{cwd}\t{cmd}` (no cwd: `bash\t{cmd}`; the agent suffix still trails, so the `bash` prefix clear reaches every key). Every leading `cd … &&` hop stays verbatim and in order (`split_cd` replaces `fold_cd`); `after_cd_prefix` strips all hops for the read-only check. A command behind a *relative* hop (`cd sub`, `cd -`, `$VAR`) is never keyed: PostToolUse already sees the cwd after the command's own `cd`, so the repeat would match its own key and run one level deeper. Absolute hops (`/…`, `~…`, a drive) stay keyed. `post_tool` clears every `bash` key for any command with a `cd` hop. The two pinned tests were rewritten and new ones cover stale-after-cd, the relative-repeat false deny and per-cwd keys.

Check: `cargo nextest run -p rtok --lib plugins::guard`; `just check`.

Status: done 2026-10-08
Model: Claude Code / claude-sonnet-5-5
