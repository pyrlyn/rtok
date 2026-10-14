# rtok vs each competitor

One file per tool from `docs/comparison.md` and `research.md` (LadybugDB and Grafeo are excluded: internal spikes, removed in P39). Benchmarks live in `bench/competitors/<slug>/` and use one fixed Colima profile (`rtok-bench`: 4 CPUs, 8 GiB, vz) and `docker run --cpus 4 --memory 8g`.

| File | Category | Measured | Verdict |
|---|---|---|---|
| [rtok-vs-rtk.md](rtok-vs-rtk.md) | Command-output filters | script written, not run yet | rtok is better overall for agent work; rtk is better at filter coverage. |
| [rtok-vs-sqz.md](rtok-vs-sqz.md) | Command-output filters | no (feasible, not written) | Close; rtok is better as a whole system, sqz has the richer filter set. |
| [rtok-vs-lean-ctx.md](rtok-vs-lean-ctx.md) | Read, search and shell MCP servers | no (feasible, not written) | rtok is better on this workload. |
| [rtok-vs-token-optimizer.md](rtok-vs-token-optimizer.md) | Read, search and shell hooks | no (feasible, not written) | rtok is better. |
| [rtok-vs-token-optimizer-mcp.md](rtok-vs-token-optimizer-mcp.md) | Read, search and shell MCP servers | no (not fairly benchmarkable) | Undecided on cost; rtok is leaner. |
| [rtok-vs-headroom.md](rtok-vs-headroom.md) | Compressing proxies | no (not fairly benchmarkable) | Roughly equal on mechanism; rtok is better as an integrated, measured stack. |
| [rtok-vs-caveman.md](rtok-vs-caveman.md) | Compressing proxies / prompt level | no (feasible, not written) | rtok is better on correctness and on its fixture bench. |
| [rtok-vs-bifrost.md](rtok-vs-bifrost.md) | Gateways | no (not fairly benchmarkable) | Different products. |
| [rtok-vs-mcpproxy-go.md](rtok-vs-mcpproxy-go.md) | Compressing proxies | no (feasible, not written) | Not ported; rtok's approach is better for its scope. |
| [rtok-vs-serena.md](rtok-vs-serena.md) | Code graphs | no (feasible, not written) | serena is better at precision; rtok is better on cost. |
| [rtok-vs-codegraph.md](rtok-vs-codegraph.md) | Code graphs | no (feasible, not written) | Close; no shared measurement. |
| [rtok-vs-code-review-graph.md](rtok-vs-code-review-graph.md) | Code graphs | no (feasible, not written) | rtok is better on cost; code-review-graph has richer review features. |
| [rtok-vs-codebase-memory-mcp.md](rtok-vs-codebase-memory-mcp.md) | Code graphs | no (feasible, not written) | codebase-memory-mcp is broader; rtok is lighter and was the one that ran here. |
| [rtok-vs-graphify.md](rtok-vs-graphify.md) | Code graphs | no (not fairly benchmarkable) | Different scope. |
| [rtok-vs-aider-repo-map.md](rtok-vs-aider-repo-map.md) | Code graphs / repo maps | no (feasible, not written) | Different packaging of the same idea. |
| [rtok-vs-universal-ctags.md](rtok-vs-universal-ctags.md) | Code graphs / tags | no (feasible, not written) | Different tools; rtok answers more questions, ctags is the cheaper baseline. |
| [rtok-vs-claude-mem.md](rtok-vs-claude-mem.md) | Memory | no (not fairly benchmarkable) | claude-mem is better if you want automatic memory; rtok is cheaper and simpler. |
| [rtok-vs-engram.md](rtok-vs-engram.md) | Memory | no (feasible, not written) | rtok is better on cost with the useful parts adopted; engram has more memory features. |
| [rtok-vs-mem0.md](rtok-vs-mem0.md) | Memory | no (not fairly benchmarkable) | mem0 is better for semantic memory; rtok is far cheaper per note. |
| [rtok-vs-graymatter.md](rtok-vs-graymatter.md) | Memory | no (feasible, not written) | Close; rtok has the stronger measured recall on its own bench, graymatter the richer ranking. |
| [rtok-vs-openviking.md](rtok-vs-openviking.md) | Memory / context DB | no (not fairly benchmarkable) | Different scope; no shared evidence. |
| [rtok-vs-mempalace.md](rtok-vs-mempalace.md) | Memory | no (not fairly benchmarkable) | rtok is better on recall fidelity; MemPalace has the admission gate rtok lacks. |
| [rtok-vs-openhuman.md](rtok-vs-openhuman.md) | Memory | no (not fairly benchmarkable) | Ideas, not a head-to-head. |
| [rtok-vs-ponytail.md](rtok-vs-ponytail.md) | Prompt level | no (not fairly benchmarkable) | Same idea; rtok's version wins its fixture bench, neither has a live bill number. |
| [rtok-vs-toon.md](rtok-vs-toon.md) | Formats | no (feasible, not written) | Complementary: rtok uses the format. |
| [rtok-vs-llmlingua-2.md](rtok-vs-llmlingua-2.md) | Formats / compression | no (not fairly benchmarkable) | Different risk profile; rtok declines it for code until a bench shows a gain. |
| [rtok-vs-portkey.md](rtok-vs-portkey.md) | Gateways | no (not fairly benchmarkable) | Different products. |
| [rtok-vs-litellm.md](rtok-vs-litellm.md) | Gateways | no (not fairly benchmarkable) | Different products. |
| [rtok-vs-anthropic-native.md](rtok-vs-anthropic-native.md) | Platform | no (not fairly benchmarkable) | Use both. |
| [rtok-vs-beads.md](rtok-vs-beads.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-gas-town.md](rtok-vs-gas-town.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-backlog-md.md](rtok-vs-backlog-md.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-task-master.md](rtok-vs-task-master.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-mcp-agent-mail.md](rtok-vs-mcp-agent-mail.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-spec-kit.md](rtok-vs-spec-kit.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-ccpm.md](rtok-vs-ccpm.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-git-bug.md](rtok-vs-git-bug.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-vibe-kanban.md](rtok-vs-vibe-kanban.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-casr.md](rtok-vs-casr.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-continues.md](rtok-vs-continues.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-session-porter.md](rtok-vs-session-porter.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-cass.md](rtok-vs-cass.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
| [rtok-vs-specstory.md](rtok-vs-specstory.md) | Tasks and session sync | no (not fairly benchmarkable) | Not a token-reduction competitor; an idea source. |
