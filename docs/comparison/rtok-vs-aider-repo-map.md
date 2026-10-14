# rtok vs aider repo map

Competitor: [aider repo map](https://aider.chat/docs/repomap.html) · category: Code graphs / repo maps · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs aider repo map

**What aider repo map does.** aider's repository map: tree-sitter tags of the repo ranked with PageRank, sent to the model as a compact file/symbol overview.

**What rtok does here.** `read` `map` mode and `graph` `outline`/`explore` (tree-sitter tags).

### aider repo map: pros
- Ranks what matters (PageRank) rather than listing everything.
- Battle-tested inside aider.

### aider repo map: cons
- Part of aider, not a standalone tool for other hosts.

### rtok: pros
- Available to 24 hosts through hooks and MCP.

### rtok: cons
- No ranking of the map by importance.

## Verdict

**Different packaging of the same idea.** aider's ranking is a good idea rtok has not adopted; rtok makes the map available outside aider.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited as a design alternative for `read` (aider 0.82, 2026-09-02); no numbers recorded | `research.md` §6 Plugin design surveys |

## Own measurements

Not measured. No script in this pass.

Feasible later: Map size in tokens for the pinned corpus is LLM-free (aider can print its map); not written in this pass.
