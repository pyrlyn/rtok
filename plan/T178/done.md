Found in the 2026-09-22 audit: in-process hook time is p50 0.3 ms, but Claude Code records p50 18–19 ms and p95 206–255 ms for PreToolUse/PostToolUse — process start of a 27 MB binary dominates and the ≤ 10 ms rule is broken on every call without rtok noticing. Ten hooks were cancelled at Claude Code's 5 s timeout (5 PreToolUse, 5 UserPromptSubmit with p50 5.6 s — no UserPromptSubmit rows exist in the store, so the owner is unconfirmed). SessionEnd (p50 18.9 ms) and PreCompact (p50 15.0 ms) are over budget in-process.

Plan: research first — measure cold/warm start (`hyperfine`), find what runs before `main` dispatches (config parse, DB open, migrations), confirm who owns the UserPromptSubmit timeouts; then pick: lazy store open, a smaller hook path, or a resident process (`rtok demon`) the hook talks to. Record findings in `research.md`.

Execution plan: (1) `hyperfine` `rtok hook PreToolUse`/`PostToolUse` with recorded payloads against `rtok --version`, release build, cold and warm; (2) trace what runs before dispatch (config load, store open, migrations, plugin registry) and the binary's load/page-in cost; (3) read the `~/.claude` hook config to name the owner of the `UserPromptSubmit` timeouts; (4) fix the largest cost at the responsible layer (lazy store open, no migrations on the hook path, lighter hook entry) — a resident process only if the rest cannot reach 10 ms, and then as its own proposed task; (5) dated `research.md` section with before/after.

Step 2 plan (D32): (a) `crates/rtok-hook`, the std-only wire format; (b) `rtok hook --serve`, one resident per home that runs `hooks::run` for each request in the client's cwd and refuses another version or config environment; (c) the `demon` service `hook`; (d) the `rtok-hook` client: connect, 50 ms answer timeout, fallback to `rtok hook`, detached autostart; (e) `hooks.json` order `rtok-hook` → `rtok` → `hook.sh`, shipping, before/after as Claude Code sees it. One PR each.

Check: a dated `research.md` row with measured start time before/after; hook p50 as seen by Claude Code under 10 ms on this machine; `just test` green.

Progress (research.md §19): the plugin launcher (a second `/bin/sh` per call) was the largest cost; `hooks.json` now execs `rtok` from PATH directly, p50 as Claude Code sees it 20.9 → 14.6 ms (PreToolUse) and 19.0 → 13.3 ms (PostToolUse). Remaining: the node + `/bin/sh` floor (5 ms) plus `rtok --version` (5.6 ms) already exceed 10 ms, so the Check needs a resident process with a small hook client — proposed as its own task. Locked store (§19.6): the hook now waits 5 ms on another writer, not 1 s per statement, and fails open with the input unchanged — 1.06–2.13 s → ~20 ms. Remaining: the resident process and hook client.

Measured (Cursor / grok 4.7, 2026-09-26): shipped `hooks.json` order `rtok-hook` → `rtok` → `hook.sh`. §19.2 node harness (`spawn` `{shell: true}`, isolated `RTOK_HOME`, resident up, release `6e608af2`, 300 rounds, load 46.23 → 39.18): `true` floor p50 5.63 ms; PreToolUse p50 **12.28** / p95 25.18 ms; PostToolUse p50 **12.54** / p95 29.22 ms. Numbers in `research.md` §19.7.

Result: The creator raised the Check (2026-09-26) to hook p50 as Claude Code sees it under **20 ms** on the §19.2 harness. §19.7 already meets it (PreToolUse p50 12.28 ms, PostToolUse p50 12.54 ms). No new measurement. Cutting further toward 10 ms stays on the roadmap (Later), not a plan card.

Status: done 2026-09-26
Model: Cursor / grok 4.7
