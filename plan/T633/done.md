Source-confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536`: [render](src/report/pdf.rs#L52) prepends one contents page, and section bookmarks use `start + 2`, but [toc_page](src/report/pdf.rs#L171) prints `start + 1`. A section starting on content index zero is physically on page 2 while the contents says page 1; subsequent section numbers have the same offset. This is separate from T632's chart-height calculation.

Execution plan:

1. Add a PDF contents regression with sections starting on different content pages, checking printed page numbers against their physical pages.
2. Share the conversion from zero-based content index to physical page number between the contents and bookmark construction, honoring the PDF library's index convention.
3. Verify first, middle and final sections plus chart continuations with extracted text/bookmarks and a rendered sample.
4. Run PDF/report tests and `just check`.

Check: every contents entry points to the page that actually starts the section. The source-arithmetic probe confirmed the one-page discrepancy; viewer navigation was not executed.

Result: One `physical_page` helper feeds both the PDF contents page and the bookmarks, so section numbers match the heading pages.

Check: `section_page_numbers_match_heading_pages`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
