Creator decision on T374 (2026-10-08). A file named in the prompt (a path `files::mentioned` finds, relative, inside the root) recalls up to 2 notes linked to it even with no text match. They take slots from the existing `prompt_recall` budget (`n` titles, `recall_tokens`) and never enlarge it; output is byte-stable. Files the session merely read only re-rank notes that already matched the prompt text, so T374's recall of read-file notes with no text match is gone. The wider variant, recall by every read file without a text match, is parked as I-115 until a `Measurement` shows a saving. `files::recall_hits` replaces `linked_notes`; no config keys, no SDK change.

Check: `cargo test --lib memory` (named file recalls an unmatched note, no named file recalls nothing extra, cap of 2, hits within `n` and `recall_tokens`, byte-identical on repeat, read file alone recalls nothing); `cargo test --test p29_memory --test memory_bench --test memory_status --test hook_fail_open`; clippy `--all-targets`; `cargo fmt --check`.
Result: lib memory 57 passed; integration targets green; clippy and fmt clean. #852.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
