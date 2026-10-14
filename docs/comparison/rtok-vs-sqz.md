# rtok vs sqz

Competitor: [sqz](https://github.com/ojuschugh1/sqz) · category: Command-output filters · sources: rtok's own docs at `de96106a9` (2026-10-14) unless a URL is given.

## RTOK vs sqz

**What sqz does.** Rust command-output filters (ELv2) with 45+ formatters, a content-hash dedup that replaces repeated bytes with a 13-token `§ref:HASH§`, JSON null-stripping, column-padding collapse and a safe mode that passes stack traces whole. Lossless (`sqz expand`).

**What rtok does here.** `cmd` plugin rules and formatters, `guard` dedup keyed by call input, a T65.1 content-hash dedup, JSON compact (T65.2) and column collapse (T65.3), all measured into one ledger and available on hook, MCP and proxy surfaces.

### sqz: pros
- Lossless, like rtok.
- Larger formatter set (45+) than rtok.
- Strong dedup idea (content hash) and a stack-trace safe mode that rtok copied as tasks (T65.1–T65.4).

### sqz: cons
- Elastic License 2.0, not OSI open source.
- All numbers self-reported (24.7 % average over 3,003 compressions).
- No proxy or MCP surface.

### rtok: pros
- Three surfaces (hooks, MCP, proxy) with the same plugin set.
- One measurement ledger including provider usage.
- Permissive licence.

### rtok: cons
- Smallest filter library of the three (rtk, sqz, rtok).
- Secrets are not redacted by rtok's safe path (sqz passes them whole, rtok cuts frames but never redacts).

## Verdict

**Close; rtok is better as a whole system, sqz has the richer filter set.** Both are lossless Rust filters. rtok wins on surfaces and measurement and has since adopted sqz's dedup, JSON and table ideas; sqz still covers more command families. No independent numbers exist for either on a shared corpus.

## Existing numbers

Vendor figures are claims unless the source says *measured*.

| Figure | Source |
|---|---|
| 178,442 tokens saved across 3,003 compressions, 24.7 % average, up to 92 % with dedup (self-reported) | `research.md` §11 (sqz README, fetched 2026-09-18) |
| Same-session SHA-256 repeats were 1.9 % of result bytes (924 sessions, 30 d) — the gate that justified T65.1 | `research.md` §11 |
| JSON share of Bash bytes 0.21 % (30 d) | `research.md` §11, T65.2 gate |

## Own measurements

Not measured. No script in this pass.

Feasible later: Same harness shape as `rtk` (the command set and fixture corpus can be reused); not written in this pass because sqz's CLI invocation and Linux release artefacts have not been checked yet.
