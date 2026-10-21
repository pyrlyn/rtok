Promoted from I-109 (Ivan, 2026-10-04). From `research.md` §16.3 #6: tools that return compact fields or tables instead of prose would let `toon` and the formatters win more often. Measured ad hoc, read-only, on the stored `calls` payloads (2026-10-05 to 2026-10-08, 23,148 tool-result bodies, 45.8 MB, 31.4 % of the 3.32 G session input when weighted by later API requests) and on the 61,369-body archive (340 MB). Free text is 61.4 % of result bytes (19.5 % of input resident, 91 % of it from `Bash`), file content 31.2 %, `path:line` records 4.8 %, tables 2.2 %, all JSON 0.4 % (0.15 % of input). Four bodies were `toon`-eligible in the window (395 B saved); on the 16 archived structured bodies the encoder logic cuts 30.6 %. The prose a tool schema can reach (MCP) is at most 0.64 % of input before any saving (0.20 % at the 30.6 % ratio), below the 1 % gate; the rest is external program output that only the `cmd` rules can shorten. No build task filed.

Check: the dated row in `research.md` §16.6 (and §16.3 #6); card closed with its number.
Result: structured JSON 0.15 % of input, schema-reachable prose ≤ 0.64 % of input, no build task. `Bash` free text (19.5 % of input) stays out of the gate (creator, 2026-10-08): no schema reaches external stdout; its breakdown is I-114. #845.
Status: done 2026-10-08

Model: Claude Code / claude-sonnet-5-5
