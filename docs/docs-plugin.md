# docs plugin

Port of `src/plugins/docs` (T455). Default off (`[plugins.docs] enabled = true`). MCP tools
`docs_resolve` (exact name/version from `./Cargo.lock`), `docs_query` (FTS snippets of one cached crate
version under `max_tokens`, measured against the full bodies considered) and `docs_get` (one item by id).
A workspace's `llms-full.txt`/`llms.txt` is split on `#` headings into crate `llms`, cached per canonical
cwd (`local:<cwd>`), refreshed only when its bytes change and dropped when the file goes.

`fetchCrate` downloads `https://docs.rs/crate/<n>/<v>/json.zst`, indexes the rustdoc JSON and writes
`<home>/docs/<n>/<v>/rustdoc.json.zst(.sha256)`. Lock entries are checked so a name or version cannot
escape the docs cache.

## Limitations
`rtok docs fetch` is not wired yet: decoding `json.zst` needs a zstd decoder, which is a new dependency
awaiting Ivan's approval. `fetchCrate` takes the GET and decode functions as parameters and is tested
with in-memory ones. The MCP and CLI surfaces arrive in phase 10.

## Tests
`DocsTest` 13 (12 Rust ports + a fetch/index round trip).
