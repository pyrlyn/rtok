From the codegraph / graphify review. graphify indexes Markdown headings as nodes beside code. rtok's `outline` and `read` mode `map` return nothing for `.md`, and T62.1's execution plan writes a first heading outliner inside `guard/skill.rs` — a second one would be a duplicate the day `outline` gains it.
Done when `plugins::read::outline` has one Markdown mode (`#` headings with their level and first non-empty body line, fenced code blocks skipped, line numbers as for code), `read` mode `map` and `outline` on `.md` / `.mdx` use it, T62.1's digest calls the same function (moved there if it landed first), no `tree-sitter-md` dependency (headings are a line scan), test on a three-heading fixture with a heading inside a fence, and the `read` docs page lists `.md` under `map`.

**Result (2026-09-18).** Commit `45c4531`. Shared Markdown outline in `src/plugins/read/outline.rs`; `guard/skill.rs` digest reuses it; `.md`/`.mdx` supported in `map` and `outline`.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
