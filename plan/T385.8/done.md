optimization.md §3 (P28, I-21). Dated `research.md` rows: share of input that is archived tool output old enough to compress, and a must-keep fixture (identifiers, paths, numbers, errors that a compressor must not drop). No compressor yet.

Check: the dated rows and the fixture are in `research.md` and `tests/fixtures/`.

Result: old enough is the shipped archive rule (`keep_turns` 4, `min_tokens` 1500). Over 2026-09-08 to 2026-10-08 (2,148 Claude Code transcripts, 144,452 requests, 21.16 G real input tokens, compaction modelled) that set is 6.54 % of input (6.13 % at keep 8, 3.23 % at min 3000, 13.76 % at min 300), about half code or diffs and half prose or logs; a 2x-5x compressor would remove 3.3 % to 5.2 % of input tokens at most (ceiling 6.5 %), in tokens not dollars (98.4 % cache hit). 8,625 of 163,019 results (99.5 MB, 0.12 % of input volume as compressor input) ever become eligible; the proxy rewrite has never run here (`archive_decisions` empty). `research.md` §16.8. Must-keep fixture `tests/fixtures/p28_must_keep.toml` (7 synthetic tool results, 54 spans of six kinds) with `tests/p28_must_keep.rs`: the fixture is validated, and the shipped lossless pointer keeps 14 of 54 spans while `expand` returns all 54 (the baseline Gate P28 starts from). Follow-up: I-116 (bench the `min_tokens` floor).

Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
