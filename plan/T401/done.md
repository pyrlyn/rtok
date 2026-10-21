`rtok mcp --http [ADDR]` serves the stdio server's tools over MCP Streamable HTTP (rmcp `StreamableHttpService`, stateless, JSON responses) so the Grok API can call them through a tunnel. The existing `Server` is reused: HTTP calls run the same allow-list, dispatch and `record`, and write the same Measurement rows as stdio. Loopback bind by default (`[mcp] http = "127.0.0.1:8791"`). A bearer token of 16+ characters from `RTOK_MCP_TOKEN` or `[mcp] token` is always required, compared in constant time, never logged and redacted in config output. Host and Origin are validated (403), and only `[mcp] public_url` adds a foreign host. `[mcp] http_tools` is the HTTP allow-list (default read, search, tree). Client-side function export is documented in `docs/research/grok-cloud-mcp.md` §10.

Check: `tests/mcp_http.rs` uses a fake client for initialize, tools/list and one `read` call; it is refused without the token or with a wrong one, and refused for a foreign Origin or Host; rows match stdio. `docs/research/grok-cloud-mcp.md` status updated; `just check`.
Result: full run 2711/2713 passed; the one load flake (`mcp_with_watcher_exits_on_stdin_eof`) passes alone, and the report HTML test now flags URL schemes instead of the bare word because the `mcp.http` key name tripped it. #834.
Status: done 2026-10-08

Model: Claude Code / claude-opus-5-5
