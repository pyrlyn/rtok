Confirmed on `ea999781d7a48733e3a5c92e711cfad291c13536` using the actual [retarget_proxy](src/agents/claude/migrate.rs#L94). `agents install claude --replace --yes` rewrites any `ANTHROPIC_BASE_URL` containing `8788` or `8787`, wherever those digits occur. The foreign URL `https://api.example/v1/8788` becomes `https://api.example/v1/8790`. The migration also hardcodes its destination instead of receiving the configured endpoint. T10.9's normal removal contract is related, but this is the separate legacy migration entry point; T626 affects Aider's ownership predicate.

Execution plan:

1. Add migration fixtures for genuine legacy loopback endpoints and foreign URLs with matching digits in hosts, paths, queries and userinfo. Assert foreign values are unchanged.
2. Parse and verify the legacy endpoint's actual authority before retargeting, using the established ownership policy and supported loopback aliases.
3. Pass the effective configured proxy destination into migration, preserving endpoint structure deliberately rather than globally replacing digit strings. Cover nondefault ports and idempotence.
4. Run Claude migration and host-config preservation tests and `just check`.

Check: migration retargets only recognized legacy rtok-related endpoints to the effective destination. The extracted production helper reproduced foreign-path mutation and passed an ordinary foreign-URL control; full installer dispatch and live Claude were not run.

Result: The Claude legacy proxy migration retargets only loopback endpoints (`agents::loopback_port`), swapping scheme and authority for `anthropic_proxy_url(cfg)` and keeping path and query.

Check: `only_legacy_loopback_endpoints_are_retargeted`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
