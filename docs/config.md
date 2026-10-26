# Configuration (`rtok-config`)

## How it works
Config is assembled from ordered layers; later layers win, tables deep-merge, arrays replace:

1. `default` – embedded `default.toml` (byte-identical to the Rust crate) and struct defaults
2. `user` – `<home>/config.toml` (or `--config FILE`)
3. `project` – `<checkout>/.rtok.toml` (git root or linked worktree root). Security-sensitive keys
   (otel, proxy upstreams, memory embed/extract, core paths, read allow_paths, cmd shell, tasks URLs,
   dashboard/mcp, compress llm, log path, wasm dir, setup paths) are dropped and shown as `ignored (project)`.
4. legacy fold – `dashboard.port` → `web.port`, `core.log_level` → `log.level`, sourced as the layer they came from
5. `dotenv` – `RTOK_*` lines from `<cwd>/.env` up to the checkout root, then `<home>/.env` (project .env cannot set restricted keys or aliases; malformed lines warn and stop that file)
6. `env` – `RTOK_*` process env (plus aliases such as `RTOK_UPSTREAM`)
7. `flag` – CLI flags

Decoding rejects unknown keys and wrong types (like serde `deny_unknown_fields`). `rtok config sources`
lists every leaf with its value and layer; `otel.headers` and `mcp.token` print as `<redacted>`.

`Validate.issues` checks a file against the schema and reports `path:line: key: message`
(enum choices, ranges, URLs, globs, since windows, task prefix, `agents.junk.extra`).
`config set KEY VALUE` edits the file line by line (keeps comments and padding, reveals a commented
default), validates the result, prints a unified diff, and replaces the file atomically (temp + rename).

## Enable / disable
Always on. Use `--config FILE` to point at another user file; delete `.rtok.toml` to drop project overrides.

## Test
`./gradlew :rtok-config:macosArm64Test` (ConfigTest, LayersTest, ValidateTest, RootTest, SinceTest).

## Limitations vs Rust
- Error texts come from kotlinx.serialization, not figment/serde; the key and line are the same.
- Process env is read for known leaf keys and aliases only (no `environ` enumeration on macOS).
- `config set` uses a line-based editor instead of toml_edit; exotic layouts (dotted keys inside
  inline tables) may be appended as a new assignment instead of edited in place.
