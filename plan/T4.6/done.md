**T4.6 PreToolUse(Read) advice** · T2.1, T4.2 · `src/plugins/read/hook.rs`
Do: for native `Read` of a file > `read.native_max_bytes` (default 32 K) that was not edited in the last 5 turns: `permissionDecision = "deny"` with reason "use rtok read(mode=map) first; native Read allowed for files you are about to edit". Config off switch. Never deny for files under 32 K (edit gate stays cheap).
Check: fixture Read of a 100 KB path → deny with reason; 2 KB path → no output.
Status: done 2026-09-02 · Check: `hundred_kb_is_denied`; `two_kb_is_silent`. `make check` green. Deviation: also `read/mod.rs`. Edit-window skip is a stub (always not-recent).
