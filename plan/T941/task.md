---
status: todo
priority: P3
complexity: 2
readiness: 0%
agent:
---

# T941. Probe: auto mode vs rtok (rewritten Bash input, 64-hex `expand` ids)

Claude Code's auto-mode classifier "gave no verdict for Bash: a hook changed this call's input"
12 times in 7 sessions (2026-10-06..12), blocking the call until it was re-issued; the rewriter
is probably the cmd plugin's `updatedInput`, but other installed hooks were not ruled out. It
also denied `rtok expand <64-hex>` 10 times in 6 sessions, the id looking like a secret.

Done when: this card names which hook changed the input, whether rtok can avoid it, and whether
pointers should print only short ids; findings in `research.md` with sources.

Check: the probe write-up; no code change unless the creator approves one.
