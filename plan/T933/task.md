---
status: todo
priority: P2
complexity: 2
readiness: 0%
agent:
---

# T933. Git guard still refuses `rtok run`-wrapped git (seen again 2026-10-16)

107 refusals "this command runs rtok with the text `git ...` in a plain command, so what it runs
cannot be shown not to be git": 89 on 2026-10-07 (cox, four subagents could not commit) and 18
on 2026-10-16 (weft subagents). T437 (#799) fixed it on 2026-10-07; the 2026-10-16 case is
either a hole in `runs_git` or an older installed binary.

Done when: the 2026-10-16 commands are replayed against current `main`; if refused, `runs_git`
accepts them and a test pins it; if allowed, this card records the binary version that refused
and closes.

Check: guard tests with the exact 2026-10-16 command lines; `just check`.
