Confirmed on `2c7c40b2d8af0673f085d71d8d3e1da0051084e5` in [Grok load](src/agents/grok/mod.rs#L295): a TOML parse error becomes a default empty document. When Claude MCP does not already cover Grok, [register_mcp](src/agents/grok/mod.rs#L209) inserts its server into that empty document and writes it over the existing configuration. An unrelated syntax error therefore discards the user's provider/settings text instead of stopping the edit.

An isolated probe compiled the verbatim registration/loader functions with a temporary config path and the real agent SDK writer. A fixture containing a user provider followed by an unfinished array was replaced by an MCP-only document. The original bytes survived in the enabled backup; a valid-provider control retained its content. Configuration and Claude-coverage dependencies were shimmed, and no real host was started.

Execution plan:

1. Add a malformed-existing-TOML regression through the Grok registration path. Require an error and byte-for-byte preservation of the original file; retain missing-file and valid-file controls.
2. Make loading distinguish a missing config from read/UTF-8/parse errors, propagating errors with the path instead of substituting an empty document. Reuse the existing strict TOML loader where its contract fits.
3. Verify install/remove/compatibility checks cannot turn a read failure into an empty writable config; test backup-disabled operation, permission failures where supported and idempotent valid installs.
4. Run focused Grok and host-config preservation tests, then `just check`.

Check: an invalid existing Grok file is never replaced by generated configuration. The executed probe covered parsing, registration and the real SDK filesystem write with backups; real CLI dispatch and OS permission failures were not exercised.

Result: Grok loads its config through the shared `agents::load_toml`, so an unparseable file is refused and left byte for byte.

Check: `an_unparseable_config_is_refused_and_left_byte_for_byte`.

Status: done 2026-10-12
Model: Claude Code / claude-sonnet-5-5
