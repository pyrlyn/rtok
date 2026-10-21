**T36.13 PDF page numbers match the pages** · — · `src/report/pdf.rs`
Do: `starts.push(pages.len())` is recorded before the pagination loop may push a fresh page, so the ToC and every bookmark name the previous page for sections that start one.
Check: in a multi-page report the ToC entry and the outline destination agree with the heading's real page.
Complexity: 2/5
Status: done 2026-09-11 · Model: Composer 2.5
Evidence: `mise exec -- cargo test --lib report::pdf` — 4 passed, including `section_page_numbers_match_heading_pages`.
