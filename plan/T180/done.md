Found in the 2026-09-22 audit: `WebSearch` 1.9 MB, `WebFetch` 1.3 MB and `Claude_Browser` `get_page_text`/`read_page` 0.25 MB in 7 days with no rtok involvement. PostToolUse cannot change native results (see T134), so the path is unclear.

Plan: list the surfaces that can reach these results (proxy, T134 outcome, an MCP fetch tool), estimate the saving on the audit sample, and propose one option as a plan change.

Check: a dated `research.md` section with the sample numbers and a recommendation.

Execution plan: (1) re-measure on `~/.claude/projects` (last 7 days, scratch script, not committed): calls, result bytes and size distribution for `WebSearch`, `WebFetch`, `Claude_Browser` and `claude-in-chrome` page-text tools; (2) per surface — proxy rewriting `tool_result` blocks in the request, a PreToolUse redirect to an rtok MCP fetch/search tool, T134's `updatedToolOutput` if honoured, and doing nothing — note what it can reach, fail-open and lossless story; (3) estimate the saving by running candidate reductions (HTML→text, dedup of repeated search snippets, head/tail with archive) offline on the sample; (4) `research.md` dated section with the numbers, one recommended option as a `roadmap.md` entry for creator approval, card to `done.md`. One docs-only PR, done before T165 so T165 reuses the scan.

Do (Claude Code / claude-opus-5-5, 2026-09-23): 7-day scan of `~/.claude/projects` deduplicated by `tool_use_id` (33,599 results): web results are 9.6 % of tool-result bytes — `WebSearch` 4.83 %, `WebFetch` 3.26 %, browser page text 0.77 %, Bash network calls 0.72 %. 48 % of `WebSearch` bytes are the `Links` JSON and only 12 % of listed links are cited in the prose; `WebFetch` is already summarised by the host except a verbatim Markdown tail. Surfaces compared in `research.md` §20.4: the proxy reaches everything but the creator runs without it; PostToolUse `updatedToolOutput` (T134) is the only path to native results in plain Claude Code sessions; an MCP `fetch` (I-92) would usually grow context. Recommendation: one web-result formatter gated on T134, proposed as I-97 in `ideas.md`.

Check result: `research.md` §20 (2026-09-23) holds the sample numbers (§20.1–§20.3) and the recommendation (§20.5); I-97 awaits creator approval.

Status: done 2026-09-23
Model: Claude Code / claude-opus-5-5
