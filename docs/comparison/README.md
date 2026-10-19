# rtok vs each competitor

One file per tool from `docs/comparison.md` and `research.md` (LadybugDB and Grafeo are excluded: internal spikes, removed in P39).

Benchmarks: Python, cross-platform entry point [`bench/competitors/run_all.py`](../bench/competitors/run_all.py).
Host only orchestrates Docker with fixed `--cpus 4 --memory 8g` (local Colima profile `rtok-bench`: 4 CPUs, 8 GiB).
Per competitor: `bench/competitors/<slug>/{run.py,bench.py,Dockerfile}`. Shared helpers in `bench/competitors/_lib/`.
Machine-readable outputs: `results.json` / `results.csv` / `results.md`. The script regenerates the `<!-- competitor-bench -->` block in each `rtok-vs-*.md`.

**Numbers in these files are pending until a real run.** Empty or `no data` means no sourced figure — never a silent zero.

Manual CI: `.github/workflows/competitor-bench.yml` (`workflow_dispatch` only; not required; not on push).

| File | Category | Measured | Verdict | Plan gap |
|---|---|---|---|---|
| [rtok-vs-aider-repo-map.md](rtok-vs-aider-repo-map.md) | code-graphs | script ready, numbers pending | Different packaging of the same idea. aider's ranking is a good idea rtok has not adopted; rtok makes the map available  | — |
| [rtok-vs-anthropic-native.md](rtok-vs-anthropic-native.md) | platform | skipped: Platform features of the host; not a third-party tool to ben | Use both. The platform is what every tool competes with; rtok's job is to not fight it. | — |
| [rtok-vs-backlog-md.md](rtok-vs-backlog-md.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for Backlog. | — |
| [rtok-vs-beads.md](rtok-vs-beads.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for beads th | — |
| [rtok-vs-bifrost.md](rtok-vs-bifrost.md) | gateways | skipped: Gateway/semantic-cache product; not a local token-reduction  | Different products. For routing use bifrost; for cutting coding-agent tokens rtok. Its semantic cache should stay off fo | — |
| [rtok-vs-casr.md](rtok-vs-casr.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for casr the | — |
| [rtok-vs-cass.md](rtok-vs-cass.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for cass the | — |
| [rtok-vs-caveman.md](rtok-vs-caveman.md) | compressing-proxies | script ready, numbers pending | rtok is better on correctness and on its fixture bench. caveman's lever is real but small in independent measurement and | — |
| [rtok-vs-ccpm.md](rtok-vs-ccpm.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for CCPM the | — |
| [rtok-vs-claude-mem.md](rtok-vs-claude-mem.md) | memory | skipped: LLM extraction + vector store; needs paid API keys. | claude-mem is better if you want automatic memory; rtok is cheaper and simpler. | T666 |
| [rtok-vs-code-review-graph.md](rtok-vs-code-review-graph.md) | code-graphs | script ready, numbers pending | rtok is better on cost; code-review-graph has richer review features. | T664 |
| [rtok-vs-codebase-memory-mcp.md](rtok-vs-codebase-memory-mcp.md) | code-graphs | script ready, numbers pending | codebase-memory-mcp is broader; rtok is lighter and was the one that ran here. | T665 |
| [rtok-vs-codegraph.md](rtok-vs-codegraph.md) | code-graphs | script ready, numbers pending | Close; no shared measurement. Similar design (SQLite, few tools). rtok's index is smaller per the author's checks, codeg | — |
| [rtok-vs-continues.md](rtok-vs-continues.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for continue | — |
| [rtok-vs-engram.md](rtok-vs-engram.md) | memory | script ready, numbers pending | rtok is better on cost with the useful parts adopted; engram has more memory features. | T667 |
| [rtok-vs-gas-town.md](rtok-vs-gas-town.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for Gas Town | — |
| [rtok-vs-git-bug.md](rtok-vs-git-bug.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for git-bug  | — |
| [rtok-vs-graphify.md](rtok-vs-graphify.md) | code-graphs | skipped: Uses LLM calls to build maps; not fairly measurable offline. | Different scope. graphify maps code and docs with an LLM; rtok indexes code without one. No shared measurement. | — |
| [rtok-vs-graymatter.md](rtok-vs-graymatter.md) | memory | script ready, numbers pending | Close; rtok has the stronger measured recall on its own bench, graymatter the richer ranking. Never cite graymatter's 83 | T669 |
| [rtok-vs-headroom.md](rtok-vs-headroom.md) | compressing-proxies | skipped: Hosted/proxy product; fair token bills need paid API keys. | Roughly equal on mechanism; rtok is better as an integrated, measured stack. headroom invented the live zone; rtok adopt | — |
| [rtok-vs-lean-ctx.md](rtok-vs-lean-ctx.md) | read-search-shell | script ready, numbers pending | rtok is better on this workload. The per-turn fixed cost of lean-ctx is larger than what its read modes save here; rtok  | T670 |
| [rtok-vs-litellm.md](rtok-vs-litellm.md) | gateways | skipped: LLM gateway/router. | Different products. | — |
| [rtok-vs-llmlingua-2.md](rtok-vs-llmlingua-2.md) | formats | skipped: Small-model compression; needs model weights/API; risky on c | Different risk profile; rtok declines it for code until a bench shows a gain. | — |
| [rtok-vs-mcp-agent-mail.md](rtok-vs-mcp-agent-mail.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for MCP Agen | — |
| [rtok-vs-mcpproxy-go.md](rtok-vs-mcpproxy-go.md) | compressing-proxies | script ready, numbers pending | Not ported; rtok's approach is better for its scope. The honest saving (17–43 % on description bytes) is a slice of a 6. | — |
| [rtok-vs-mem0.md](rtok-vs-mem0.md) | memory | skipped: Vector/LLM memory stack; typically needs Docker + Qdrant + A | mem0 is better for semantic memory; rtok is far cheaper per note. | T668 |
| [rtok-vs-mempalace.md](rtok-vs-mempalace.md) | memory | skipped: Embedding-based admission; needs model/API. | rtok is better on recall fidelity; MemPalace has the admission gate rtok lacks. | T671 |
| [rtok-vs-openhuman.md](rtok-vs-openhuman.md) | memory | skipped: Memory admission ideas; no shared offline metric. | Ideas, not a head-to-head. rtok tracked the gate (I-59) and decay (I-60) and measured tinyjuice's idea (I-71) without bu | — |
| [rtok-vs-openviking.md](rtok-vs-openviking.md) | memory | skipped: Multi-level context DB with session compression; no shared o | Different scope; no shared evidence. | — |
| [rtok-vs-ponytail.md](rtok-vs-ponytail.md) | prompt-level | skipped: Prompt-only; live bill needs a model. | Same idea; rtok's version wins its fixture bench, neither has a live bill number. | — |
| [rtok-vs-portkey.md](rtok-vs-portkey.md) | gateways | skipped: Hosted LLM gateway. | Different products. | — |
| [rtok-vs-rtk.md](rtok-vs-rtk.md) | command-output-filters | measured 2026-10-14 (Docker, 29 cases) | rtok is better overall for agent work; rtk is better at filter coverage. rtk shrinks more command families out of the bo | T688 |
| [rtok-vs-serena.md](rtok-vs-serena.md) | code-graphs | script ready, numbers pending | serena is better at precision; rtok is better on cost. Use serena when exact references matter more than ~1.5 K tokens p | T663 |
| [rtok-vs-session-porter.md](rtok-vs-session-porter.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for session- | — |
| [rtok-vs-spec-kit.md](rtok-vs-spec-kit.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for Spec Kit | — |
| [rtok-vs-specstory.md](rtok-vs-specstory.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for SpecStor | — |
| [rtok-vs-sqz.md](rtok-vs-sqz.md) | command-output-filters | script ready, numbers pending | Close; rtok is better as a whole system, sqz has the richer filter set. Both are lossless Rust filters. rtok wins on sur | T662 |
| [rtok-vs-task-master.md](rtok-vs-task-master.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for Task Mas | — |
| [rtok-vs-token-optimizer.md](rtok-vs-token-optimizer.md) | read-search-shell | script ready, numbers pending | rtok is better. Same ideas, but one fast process instead of a Python hook chain, and a measured ledger instead of a self | — |
| [rtok-vs-token-optimizer-mcp.md](rtok-vs-token-optimizer-mcp.md) | read-search-shell | skipped: Needs a live MCP client plus LLM turns; no shared offline co | Undecided on cost; rtok is leaner. token-optimizer-mcp has the better published evidence (a holdout with quality), rtok  | — |
| [rtok-vs-toon.md](rtok-vs-toon.md) | formats | script ready, numbers pending | Complementary: rtok uses the format. | — |
| [rtok-vs-universal-ctags.md](rtok-vs-universal-ctags.md) | code-graphs | script ready, numbers pending | Different tools; rtok answers more questions, ctags is the cheaper baseline. | — |
| [rtok-vs-vibe-kanban.md](rtok-vs-vibe-kanban.md) | tasks-session-sync | skipped: Coordination/session tool; no shared token-reduction metric  | Not a token-reduction competitor; an idea source. rtok's verdict on its features is in `research.md` §28.4; for Vibe Kan | — |

