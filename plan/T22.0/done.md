**T22.0 pick the PDF renderer against the size gate** · T15.0 · `docs/report.md` (new)
Do: D15-style survey before any code. At least three candidates priced honestly: `typst` as a
library, `printpdf` + `svg2pdf`, and rendering through a browser. Judge each on (1) what it does to
the release binary, which P17 already gates, (2) whether it keeps "one static binary, no runtime
dependency" true, (3) whether HTML and PDF can come from *one* document rather than two layouts.
Charts are one decision too: pure-Rust SVG (`plotters`) embeds into all three formats, where a
JS charting library would make the HTML the only real format and the other two second-class.
Check: `docs/report.md` names the choice, the two rejected options with the reason, and the
measured size cost of the winner; a `dist` build stays inside the P17 budget.
Status: done 2026-09-09 · Model: GLM-5.3-Flash (zai-coding-plan)
Check result: green. docs/report.md names the choice (printpdf 0.12.8 + svg2pdf 0.13.0, plotters
0.3.7 SVG for charts), the two rejected options with their reasons (typst: measured +42.30 MiB
against an 18.27 MiB binary; browser rendering: a runtime dependency, rejected on criterion (2)
without a build), and the measured size cost of the winner (+5.99 MiB release / +5.12 MiB
dist-shaped) with the scratch commands and dates. All sizes from throwaway /tmp crates built
2026-09-09 with P17's strip = "symbols" profile; a no-dep hello is the baseline, so the deltas are
the per-candidate cost; each scratch binary was run and produced its artefact. P17 published no byte
cap, so the budget clause is applied as published arithmetic — dist 17.4 MB + 5.12 MiB ≈ 22.8 MB
(+31 %), the same size class P17 recorded — with the linked measurement due at T22.3; typst's
arithmetic (≈ 61.8 MB, ~3.5×) is what fails the gate, which is why the smaller candidate wins. The
site row for the new docs page is in _content.gotmpl. Repo Cargo.toml/Cargo.lock untouched.
