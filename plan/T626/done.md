Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [is_ours_value](src/agents/aider/mod.rs#L149). Ownership accepts `localhost` or `127.0.0.1` anywhere in the value, plus the configured port anywhere with a non-digit boundary. [strip_ours](src/agents/aider/mod.rs#L213) consequently deletes an unrelated `openai-api-base: https://api.example/localhost:8790/v1` when the rtok port is 8790, even though the URL authority is `api.example`. The same predicate reports that endpoint as rtok-installed.

An isolated Rust probe compiled the actual parser, predicate and remover: the foreign endpoint disappeared while an unrelated `model` line remained. A conventional foreign endpoint was kept and rtok's exact loopback endpoint was removed. T559 concerns quoted YAML keys, not URL ownership; the existing foreign-base-URL test does not cover loopback text outside the authority.

Execution plan:

1. Add ownership/removal regressions for loopback strings and port-like text in paths, queries, userinfo and foreign hostnames. Assert foreign configuration remains byte-for-byte unchanged.
2. Parse the endpoint and compare its actual scheme, host and port against the supported rtok ownership contract. Preserve exact configured URL recognition and intentional loopback aliases; do not infer ownership from arbitrary substrings.
3. Use the same predicate for installed-state detection and removal. Cover IPv6 loopback if supported, malformed URLs, port-prefix collisions and quoted YAML values without duplicating T559's parser work.
4. Run focused Aider detection/removal tests and `just check`.

Check: a foreign URL containing loopback-looking text outside its authority is neither reported as rtok's proxy nor removed. Executed evidence used extracted production parsing/removal code; full CLI file edits and real Aider were not run.

Result: A shared `agents::loopback_port` parses the URL (`url` crate) and returns the port only for an http(s) loopback host; Aider ownership uses it instead of substrings.

Check: `ownership_reads_the_authority_not_substrings`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
