T329 §1 (index status) and the CLI half of §7. `rtok graph projects [--json]` lists every registered project: selected marker, id, name, origin, state (`ok`, `stale`, `not indexed`, `missing`), rows, files, pending and root. `add <path>` registers an existing directory (a known one only refreshes last-used), `select <id|path>` selects one, `remove <id|path>` drops its index rows and registry row. `--json` is global on the group and prints the same rows (a missing root has `index: null`).

Execution: `src/plugins/graph/projects.rs` (new) builds each row from `Store::projects()` (T329.1) and `graph::status::collect`, the same numbers `rtok graph status` prints, and renders through `render::table`; clap enum and arm in `src/cli.rs`. `<id|path>`: a number naming a known project is its id, anything else is a directory. `select` refuses a missing root, `remove` never touches files. The surface-parity gate exempts the four commands with a reason (the page selector is T329.12); README and the site command table list `graph projects`.

Check: `tests/graph_projects.rs` (round trip over a fixture HOME: add, index status ok and stale and not indexed, select exclusivity, remove leaving files, bad targets, a deleted root listed as missing and not selectable), `projects::tests::resolve_prefers_a_known_id_then_a_path`, five trycmd cases (`tests/trycmd/graph-projects*.toml`), completion and help goldens regenerated; `just check`.

Deviations: the card's "last error" index status is not shown, because nothing records an index failure yet; it comes with T329.8. No `docs/ru` or `docs/uk` exist, so there was nothing to update there.

Status: done 2026-10-03 · Model: Claude Code / sonnet-5
