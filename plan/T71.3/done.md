From I-52. Nothing tells a model that `expand <id>`, `read` modes, `mem_search` or `symbol` exist unless the human writes it into `CLAUDE.md`; a skill is the host-native way.
**Creator confirmed (2026-09-18): one hub skill per host, not one per surface.**
Done when:
1. `skills/rtok/SKILL.md` in the repo: description ≤ 120 chars, body ≤ 2 KB (a `tests/skill.rs` check on both), `disable-model-invocation` unset, body = when to use `expand`, `read` modes, `search` / `tree`, the memory and graph tools, each one line pointing at its `docs/` page — no second copy of the docs.
2. `rtok agents install <host>` copies it into the host's documented skill root for every host whose format is in `research.md` §10.1 (Claude Code `~/.claude/skills/rtok/`; others per that table), `remove` deletes only that directory, both idempotent and byte-stable; hosts without a skill format are untouched. Through `rtok-agent-sdk` (D28), one write cycle with the plugin offer.
3. `doctor`'s skill section (T61.3) lists it like any other skill; its description bytes on this machine go into `research.md` §10.2.
4. `Vfs` tests: install, re-install (no change), remove (foreign skills kept); `tests/host_docs.rs` covers the skill-root doc link per host; `docs/agents.md` host table re-blessed if `support()` changes.

**Result (2026-09-18).** Branch `t71.3`. One hub `skills/rtok/SKILL.md` (description 112 chars, body 780 B); `SkillCopy` in rtok-agent-sdk copies it during `rtok agents install <host>` for claude, cursor, codex, opencode, copilot and skips hosts without a §10.1 skill format. `Vfs` tests cover install / reinstall / remove; doctor lists the hub like any other user skill; description bytes recorded in `research.md` §10.2. `support()` unchanged — no `docs/agents.md` re-bless.

**Check:** `cargo test --lib agents::skill`, `skills_audit_lists_the_rtok`, `-p rtok-agent-sdk skill_`, `--test skill --test host_docs` pass. Full `just check` is blocked on pre-existing HEAD fmt/clippy in unrelated files.

---
