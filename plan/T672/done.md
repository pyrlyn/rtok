Best competitor: sqz `§ref§` ~13 tok. Design: one short id `[rtok 1a2b3c4d · 111 lines]`, expand by prefix.

Check: #1076 harness (`bench/c14`) shows −75 tok/call on this category with full anchor retention.

Result: trailer, the rules' `… omitted (expand <id>)` marker and the same-session dedup pointer print the 8-hex-char id (`names::short_id`; `expand` already resolved 8+ char prefixes). A prefix shared by two archives now errors with each candidate named by a 12-char prefix instead of 'pass more characters'. Measured with `tests/bench_fixtures.rs` (o200k_base; #1076 `bench/c14` is not on main): the pointer line costs 12–14 tokens per call (default minus `--no-ref`, e.g. cargo_build_error 58 vs 44), in line with sqz's ~13-token `§ref§`; the 64-char ids in the omitted and dedup lines (≈ 35 tokens each) are gone. Tests: `trailer_names_an_eight_char_id`, `short_id_is_the_resolvable_minimum`, `ambiguous_prefix_lists_the_longer_candidates`; trycmd `filter` and the MCP quoted-range test updated.

Status: done 2026-10-15
