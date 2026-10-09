# Plugins

Every token-reduction method in rtok is a plugin behind one trait. Plugins are in-tree
modules behind Cargo features — no daemon, no subprocesses, no WASM in v0.1.

The **replaces** column is a specification target, never a dependency: rtok reimplements the
behaviour from scratch and never runs, links, or reads the tool named.

| Plugin | Replaces (spec only) | Surface |
|--------|----------------------|---------|
| [`measure`](../src/plugins/measure/README.md) | rtk gain, headroom savings, lean-ctx gain | `stats`, `bench`, proxy |
| [`cmd`](../src/plugins/cmd/README.md) | rtk hook, ctx_shell, bash_compress | PreToolUse(Bash) → `rtok run` |
| [`read`](../src/plugins/read/README.md) | lean-ctx read/search/tree, read_cache | MCP `read` |
| [`json_tree`](../src/plugins/json_tree/README.md) | — | proxy, MCP |
| [`archive`](../src/plugins/archive/README.md) | CCR | `expand`, store |
| [`proxy`](../src/plugins/proxy/README.md) | caveman-proxy | `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` |
| [`inject`](../src/plugins/inject/README.md) | caveman/ponytail, lean-ctx | SessionStart, UserPromptSubmit |
| [`guard`](../src/plugins/guard/README.md) | — | PreToolUse |
| [`memory`](../src/plugins/memory/README.md) | claude-mem | MCP, PreCompact |
| [`graph`](../src/plugins/graph/README.md) | codebase-memory-mcp | MCP |
| [`toon`](../src/plugins/toon/README.md) | — | MCP |

## Turning plugins off

At runtime, in `~/.rtok/config.toml`:

```toml
[plugins.cmd]
enabled = false
```

Or at build time, so the code is not compiled in at all:

```bash
cargo build --no-default-features --features cmd,read
```

## Each plugin directory

| File | Holds |
|------|-------|
| `README.md` | what the plugin does and why — linked from the table above |
| `AGENTS.md` | invariants and task rules for whoever works on it |
| `PLAN.md` | the plugin's own build plan |

Writing your own: [plugin authoring](plugin-authoring.md).
