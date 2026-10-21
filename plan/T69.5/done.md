From the graymatter gap review (`research.md` §14). graymatter's `UserPromptSubmit` hook does two things: a prompt starting with `remember: <text>` is saved instantly, and every turn injects the top-3 facts recalled for the prompt. rtok's `UserPromptSubmit` carries nothing from `memory`: saving a note costs a `mem_save` tool round trip (an API turn plus the output tokens of the call) even when the human typed the fact; per-turn recall would cost ~30–60 tokens per turn, unmeasured — and graymatter's own table compares against full-history injection, not against no injection.
Done when:
1. The SDK gains `Plugin::user_prompt(&self, ev: &UserPrompt, cx: &Ctx) -> Option<Injection>` with a no-op default (semver-minor; `inject` keeps the budget and the byte-stability test for modes), dispatched from `rtok hook UserPromptSubmit`.
2. `memory` handles it: a prompt whose first line matches `^remember:\s*(.+)` saves kind `user`, title = the first 80 chars of the rest, body = the rest (through the in-place `mem_save` path, so a repeat is a no-op), project from the hook cwd, and answers with one line `saved note <id>`; any other prompt → no output; the hook stays ≤ 10 ms and fails open (store error → nothing). The prompt is stored only as that note, never logged. Hook e2e: with and without the prefix; a second identical save returns the same id.
3. `[plugins.memory] prompt_recall = 0` (0 = off; N = titles per turn): when N > 0 the hook runs the T69.2 ranking on the prompt's words and offers N `id title` lines at priority 11 inside the D5 budget; a turn whose top-N equals the previous turn's (per-session sha in the store) emits nothing; `Measurement { plugin = "memory", kind = "prompt_recall" }`. Stays 0 by default until a `rtok bench` A/B (T53.1 shape) shows cost per passed task does not rise; the card records the dry result.
4. `docs/config.md` rows, the memory page, and `docs/agents.md` re-blessed if the host table changes.

**Result (2026-09-18).** Commit `45c4531`. `Plugin::user_prompt` dispatched from `UserPromptSubmit`; `memory` plugin saves `remember:` notes in-place and supports `prompt_recall` config (default 0).

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
