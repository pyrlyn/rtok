Creator's request 2026-09-24: every agent plugin package's `README.md` must link the host's official documentation.

Result: `plugins/AGENTS.md` gains an `Official documentation links` rule: each `plugins/<host>/README.md` MUST have an `Official documentation` section linking the vendor's own docs for packaging, hooks, MCP config and skills; canonical vendor pages only; every relied-on contract traceable to a link; undocumented surfaces say so and link the verified source; links re-checked on every package change. The mandatory-docs table and the new-host checklist point at it. Existing READMEs are not yet brought in line (none has the section).

Check: `cargo nextest --test host_docs --test surface_parity`.
