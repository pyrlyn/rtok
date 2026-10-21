Promoted from I-110 (Ivan, 2026-10-04). From `research.md` §16.3 #8: repeated long paths and identifiers could be replaced with short codes plus one legend per request, if that does not cost answer quality or break the prompt cache. Done meant: measure first; build it behind a proxy flag and A/B it only if it is above 1 % of input.

The creator's database holds no proxy request bodies, so the measurement runs on the transcripts, which carry the message array the proxy would forward. A new `dictionary` row in `rtok stats` (`src/measure/dictionary.rs`) rebuilds up to 6 requests per session (reset at compaction) and counts paths (`/` runs of 16+ bytes) and identifiers (snake_case or camelCase of 12+ bytes) repeated in a request. The row is absent, and the JSON omits it, when nothing repeats, so existing goldens hold. Over 30 days (306 sessions, 1,672 requests, 233 M billed input tokens) repeated bytes are a 2.05 % ceiling, but the cache-safe saving (a code defined at a token's second use in a tool result, earlier bytes never rewritten) is 0.44 %: under the gate, so the dictionary is not built and no A/B was run. `research.md` §16.7.

Check: `rtok stats --since 30d` dictionary row; dictionary unit tests; `stats_model` goldens unchanged; `just check`.
Result: 0.44 % cache-safe (paths 0.26 %, identifiers 0.18 %), 2.05 % ceiling; `just check` green (306 tests passed); not built. #853.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
