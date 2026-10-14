# rtok vs Universal Ctags

Competitor: [Universal Ctags](https://github.com/universal-ctags/ctags) · category: Code graphs / tags · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs Universal Ctags

**What Universal Ctags does.** The classic tag generator: cheap symbol definitions for hundreds of languages, no call graph.

**What rtok does here.** `graph`: tree-sitter tags plus references, callers and impact in SQLite.

### Universal Ctags: pros
- Fast, tiny, ubiquitous, huge language coverage.

### Universal Ctags: cons
- Definitions only; no references or call graph; no agent surface.

### rtok: pros
- References, callers, impact, MCP surface.

### rtok: cons
- Fewer languages.

## Verdict

**Different tools; rtok answers more questions, ctags is the cheaper baseline.**

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| Cited as a design alternative for `graph` (6.x, 2026-09-02); no numbers recorded | `research.md` §6 Plugin design surveys |

## Own measurements

Not measured. No script in this pass.

Feasible later: Index time and size on the pinned corpus are LLM-free and a good baseline; not written in this pass.
