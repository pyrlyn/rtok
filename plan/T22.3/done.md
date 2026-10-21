**T22.3 `--format pdf`** · T22.0, T22.2 · `src/report/pdf.rs` (new), `tests/report_pdf.rs` (new), `src/cli.rs`, `src/report/mod.rs`, `Cargo.toml`, `Cargo.lock`
Do: the renderer T22.0 chose, over the same document and the same SVG charts. Paged, with a table
of contents.
Check: the PDF has the same section headings in the same order as the HTML, and the release binary
still passes the P17 size gate with the renderer linked in.
Complexity: 4/5
Status: done 2026-09-10 · Model: Muse Spark 1.3 (subagent-pdf)
Check result: green. `src/report/pdf.rs` (new) renders the same eight sections in the P22 order
over the same `Document` — A4, Helvetica only (no font files ship, which is why T22.0 picked this
renderer), a contents page with page numbers plus viewer bookmarks, native bar charts over the
identical pairs, order and titles as the HTML bars, streams uncompressed so every figure stays
greppable in the artifact. `tests/report_pdf.rs` (new) 2 passed:
`pdf_has_the_html_headings_in_order_and_the_charts` walks `--format html` and `--format pdf` over
one store — the eight `(Title)` headings in the same order in the contents list and the body, one
chart label per series, ≥2 `/Type/Page`s, `%PDF-`/`%%EOF`;
`empty_store_pdf_keeps_all_headings` keeps all eight headings with `(No rows in window.)`. CLI:
`Pdf` joins the `ReportFormat` ValueEnum (D14); the shared `emit()` sink now takes bytes (the PDF
is binary; md/html/ai pass `.as_bytes()`, stdout bytes unchanged). Verified in a detached worktree
at `b7d4c2e` with an isolated target dir (the shared-target contention T22.4 noted):
`cargo fmt --check` clean, `cargo clippy --workspace --all-targets --all-features -D warnings`
clean, `cargo test --workspace` green (all suites incl. doctests), `cargo build
--no-default-features --features measure` silent, `just dup` exit 0 (38 clones, none from the new
files). P17, linked measurement (release, `strip = "symbols"`, isolated target dir):
release `rtok` 20 988 304 B → 23 212 128 B (delta +2 223 824 B / +2.12 MiB, +10.6 %),
measured 2026-09-10 on the pre-T15.8 tree plus this task (T15.8/T15.3 landed after the measurement; CLI/model lines only, same size class) — inside T22.0's projected +5.99 MiB
class (its scratch over-estimated: a hello-binary baseline pulls different dependency
paths than rtok's real feature set); P17 holds (no byte cap published, same size class). An empty-store PDF is 22 595 B over 4 pages; its xref offsets were validated
object-by-object with a dependency-free script (31 live objects, headings ordered).
Deviations: ~720 added lines across 4 tracked files + 2 new (brief: ≤200) — the Check's own
content (8 evidenced sections plus the Op-stream layout T22.0 priced as "a second layout by
construction", plus the walk-both parity test) does not fit 200; precedent T22.1 (+880), T22.2
(+370) and T22.4 (+750). `svg2pdf` is not linked although T22.0 named it: verified in its 0.13.0
source that `to_pdf` returns a standalone one-page PDF, and printpdf 0.12 has no PDF-page import
(only `UseXobject` for registered objects) — merging the two would be byte-level PDF surgery, far
outside this task, so the charts are the same series drawn natively (T22.0's own scratch produced
two separate files, never a merged page; its fontdb-on-bare-Linux hazard goes away with it). New
dependency: `printpdf 0.12.8` — the paged-PDF renderer the T22.0 survey chose.
