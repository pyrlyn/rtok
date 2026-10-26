# MCP config core (`:rtok-mcp`, package `rtok.mcp.config`)

Port of `crates/rtok-mcp`. One call path for writing rtok's own MCP entry into a host's config file.

## How it works
- `McpSpec` (Spec.kt): host, client (CLI/desktop/IDE), config path, `Format` (Json, Jsonc, Toml), dotted key
  path (`mcpServers`, `mcp_servers`, `mcp.servers`), entry shape and launch override. `entry()` builds the JSON
  entry and `summary()` builds the report line.
- `McpConfig` (Config.kt): `readEntry`, `readServers`, `writeEntry`, `removeEntry`. All I/O goes through `McpFs`,
  so tests use an in-memory map. Strict JSON is rewritten pretty. A root or table that is not an object is an
  error, and the file is left as it was.
- `JsoncEdit` is a lossless JSONC editor: it sets or removes one property and keeps comments, trailing commas
  and the formatting around it.
- `TomlEdit` keeps TOML formatting. The entry is written as a standard `[<keyPath>.rtok]` table, and created
  parents stay implicit. A one-line `rtok = { … }` inside `[<keyPath>]` is replaced or removed too.
- `apply` (Status.kt) installs, updates or removes an entry. Removal runs `judgeOwned` from `:rtok-agent-sdk`
  (T246): an entry that is not rtok's is left alone, and one the user edited is kept unless `--yes` or a yes.
  `status` compares a file against `entry()`.

## Enable / disable
This is library code. Hosts call it from `rtok agents install|update|remove` once they are wired, and
`[setup] mcp = false` turns the writes off at the caller.

## Test
`./gradlew :rtok-mcp:macosArm64Test` runs OpsTest (the 7 Rust ops/config cases plus the strict-JSON refusal),
EditorsTest (JSONC/TOML format preservation) and JsoncTest.

## Limitations
`TomlEdit` refuses to remove an rtok entry spelled as dotted keys spread over the file
(`mcp_servers.rtok.command = …`). Remove such an entry by hand.
