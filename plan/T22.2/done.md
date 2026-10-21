**T22.2 `--format html`** · T22.1 · `src/report/html.rs`
Do: the same sections, one self-contained file — inline CSS, inline SVG charts, no network fetch,
openable from a file:// URL. Charts where a series exists (savings over time, tokens per plugin,
latency distribution, cache busts per turn); tables everywhere else.
Check: the HTML contains every number the Markdown contains, asserted by a test that walks both;
the file opens with no external request (no `http` outside code blocks).
Complexity: 3/5
Status: done 2026-09-09 · Model: Muse Spark (meta/muse-spark) subagent XHIGH
Check result: green. `src/report/html.rs` (new) renders the same eight sections in the same order —
one `<h2>` per Markdown `##`, same names, stable `id`s; tables mirror the Markdown cells and
sentences, so every figure keeps its evidence. Charts are inline SVG bars over the series the
model has — saved tokens per plugin, calls per surface, busts per cause; the model carries no
per-turn series (savings over time, latency distribution), so those stay tables. Config values ride
in `<code>` and Doctor in `<pre><code>`, so the only `http` in the file is the two upstream URLs
in code spans — the document opens from `file://` fetching nothing. CLI: `Html` joins the
`ReportFormat` ValueEnum (D14), `"html"` renders through a shared `emit()` sink T22.1's `md` arm
now uses too (same bytes to stdout or `--out`, no second sink). `tests/report.rs`
`html_holds_every_markdown_number_and_fetches_nothing` walks both over the fixture store: every
digit-led run in the Markdown is in the HTML, the eight headings in order, exactly 3 `<svg>`,
no `http` outside code. Verified in a detached worktree at `a054922` with only this task's files
(the shared checkout carries T22.4/T22.5/T25.x in flight): `cargo test --test report` 4 passed,
`cargo test --workspace` green (208 lib + all suites, 0 failed), `cargo clippy --workspace
--all-targets --all-features -- -D warnings` clean, `cargo fmt --check` clean, `cargo build
--no-default-features --features measure` rc=0, `just dup` exit 0 (38 clones, none from the new
files). An empty-store HTML was also eyeballed: balanced tags, 8 headings, 2 tables, no charts.
Deviations: ~370 added lines across 3 files + the test (brief allowed the test extra) vs ≤200 —
the Check's own content (8 evidenced sections + 3 charts + walk-both test) does not fit 200;
precedent T22.1 needed +880 for Markdown tables alone, and most of the renderer's 275 lines are
`rustfmt`'s chain splits, not logic. Charts are hand-rolled SVG bars, not T22.0's `plotters`: no new dependency (one-line
reason unnecessary — there is none), no font payload, and the SVG strings stay `svg2pdf`-ready
for T22.3, which consumes SVG bytes rather than plotter objects; revisit if T22.3 needs axes.
The 3-line latency formatter mirrors `markdown::ms` instead of sharing it — sharing would edit a
fourth file for 3 lines, and T22.4's `pub(crate)` share is the natural home when it lands.
`report_flags` needed no change (`Html != Md` already inserts `format = "html"`); the
`default.toml` / `docs/config.md` format comments still read `html (T22.2)` and are left for the
owner — T22.4 is editing those same lines.
