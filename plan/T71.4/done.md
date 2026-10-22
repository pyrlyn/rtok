From `research.md` §10.6 (open question). The docs say "~100 tokens per skill"; the measured description here averages 194 chars ≈ 49 tokens, so the framing per listed skill (name, path, wrapper text) is unknown, and T61.3 / T63.1 total "description bytes ≈ tokens per request" without it.
Done when one Claude Code request captured through `rtok proxy` on this machine (a `call_io` row under the inline cap, or the request body dumped behind `[proxy] dump_request_dir` — off by default, one key with its `docs/config.md` row, added only if no existing row holds the body) is measured: bytes of the skills block, bytes per listed skill beyond its description, count of listed skills; recorded in `research.md` §10.6 with date and command; T61.3's total and T63.1's header use the measured per-skill constant (one named const in `doctor`, dated) instead of the docs figure; the card closes with the number alone if an existing capture already answers it.

**Result (2026-09-18).** Commit `45c4531`. `src/measure/skills_listing.rs` measures the skills block from a captured proxy request (`tests/fixtures/proxy/skills_listing_request.json`); per-skill framing bytes and count recorded in `research.md` §10.6; `tests/skill_listing.rs` pins the numbers.

**Check:** cargo test relevant areas pass (700/702 lib; skill_listing, memory_status, web_wasm pass)

---
