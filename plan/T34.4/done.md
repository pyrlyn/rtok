**T34.4 report: one bar scale, UTF-8-safe PDF text** · T22.1 · `src/report/{mod,html,pdf}.rs`
Do: `pdf::bars` cut labels with `&label[..26]`, which panics inside a multi-byte character; `split_word` chunked bytes and printed U+FFFD. HTML and PDF each scaled bars.
Check: `report::bar_shares` shared; char-based truncation and splitting. Tests `bars_truncates_long_multibyte_label_without_panicking`, `split_word_splits_on_chars_not_bytes`, `bar_shares` cases.
Complexity: 2/5
Status: done 2026-09-10 · Model: Sonnet 5 (subagent)
