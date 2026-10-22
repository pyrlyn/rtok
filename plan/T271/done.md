Observed by the creator on 2026-09-26: rtok 0.8.0; Claude Code 2.1.267 in the Claude desktop app's Code tab; `rtok@rtok` installed. Every session sees rtok's 14 MCP tools twice, and so does every subagent it spawns:

- `mcp__rtok__*` comes from `mcpServers.rtok` → `/Users/<user>/.ketch/bin/rtok mcp` in `~/Library/Application Support/Claude/claude_desktop_config.json`.
- `mcp__plugin_rtok_rtok__*` comes from the plugin's `scripts/mcp.sh`, which execs the same binary.

The cost is two `rtok mcp` processes and 28 tool schemas instead of 14 in every agent's context. It breaks D21 (one call path per capability, a singleton), and with it D18 and measurement.

Why T243/T244 did not catch it:
- **Write side.** `Claude::apply` for `Kind::Desktop` already drops that entry while `code_serves_mcp` is true. `rtok agents install claude --desktop --dry-run` plans `- mcpServers.rtok` on this machine now. The removal only runs when `agents install`/`update` runs for the desktop variant after the plugin exists. An entry written before the plugin was installed, or left when the plugin came through `/plugin install` inside Claude Code, stays until then. (Within one `agents install claude` the CLI variant runs first, so a fresh install is not affected.)
- **Read side.** The read side hides it. `Claude::installed(Kind::Desktop)` returns `mcp` when the file has an `"rtok"` entry *or* Code serves MCP. So `rtok agents info claude` and `rtok doctor` print `✓ mcp installed` for Claude Desktop and never say the server is there twice.

Done means:
1. **Detect.** `rtok doctor` and `rtok agents info|list` report a desktop `mcpServers.rtok` entry while `code_serves_mcp` is true as a warning: rtok's MCP is served twice in the desktop Code tab. The warning names the fix, `rtok agents install claude --desktop`. The `✓` line no longer covers that state.
2. **Sweep.** Every rtok path that leaves Code serving rtok's MCP also runs the desktop removal: the plugin step of `agents install claude`, `agents update`, and `doctor --fix` if it exists. That removal is `unregister_mcp_ours`, with T246's ownership check, so an entry the user edited is left and reported. Decide in this task whether the plugin's own `SessionStart` may do the sweep too. It fires once per session, but it must stay fail-open, and the 10 ms rule applies. Record the outcome as a decision row if it is yes.
3. **Tests (Vfs).** A desktop entry plus the installed plugin → doctor warns and `agents info` shows the warning. The plugin step alone removes an unchanged desktop entry. An edited entry is kept with a `leave` line.

Check: the tests above pass; on the creator's machine the Code tab lists one set of rtok tools after the fix path runs; `just check`.

Result (2026-10-07, creator decision): closed without code, superseded by T275 and D33. The plugin ships no `.mcp.json` any more, so Claude Code gets rtok's MCP only from `mcpServers.rtok`, which install and update always write; the desktop entry is the one Claude Desktop chat needs and must not be swept. The double tool set seen on 2026-09-26 came from the old plugin build (`cache/rtok/rtok/0.0.1/.mcp.json`); with plugin 0.15.1 installed the Code tab lists `mcp__rtok__*` only. The T171 `duplicate:` check this card relied on is no longer in `src/doctor.rs`.

Status: done 2026-10-07
Model: Claude Code / claude-opus-5-5
