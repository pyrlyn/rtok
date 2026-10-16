# Examples

Worked examples of rtok's main surfaces, with abridged real output.

## Examples

### Price the stack you already have

`rtok doctor` reads your host's settings and reports what every installed tool costs per
turn — before you install anything of rtok's. Abridged real output:

```text
hooks 88
  PreToolUse 14
  PostToolUse 12
  SessionStart 14
  UserPromptSubmit 9
  …
mcp
  code-review-graph (30 tools, ~2295 desc tokens)
  serena (22 tools, ~1494 desc tokens)
  lean-ctx (12 tools, ~697 desc tokens)
  rtok (11 tools, ~143 desc tokens)
proxy 8788→8787
mcp_tool_search likely disabled (ANTHROPIC_BASE_URL is set)
autoCompactWindow 300000
skills (68 listed, 12963 desc bytes ≈ 3240 tokens per request)
  design-is plugin:claude-mem desc 374c body 18403B calls - WARN desc>120 WARN body>8K …
  caveman-setup user desc 1c body 10304B calls - WARN body>8K (references/) …
```

Those description tokens are re-sent on every request of every session. That is the number
most tools do not count against themselves. The skills section (2026-09-18) prices the same
listing for the host's skills: 68 listed skills ride as ≈ 3.2 K description tokens per
request, and the `WARN` flags name the bodies that should live in `references/` instead of
`SKILL.md` — measured on this machine by the T61.3 audit.

### Run a command without paying for its output

```bash
rtok run -- cargo test
```

`rtok run` executes the command through your shell, keeps the exit code, writes the raw
output to `~/.rtok/archive/<id>`, and prints a per-family summary. Anything over 40 lines
gets a trailer naming its id:

```text
[rtok 7f3a91c04b2e · 412 lines]
```

Nothing is redacted, a non-zero exit prints the last 80 lines verbatim, and the original is
one command away:

```bash
rtok expand 7f3a91                    # the whole thing
rtok expand 7f3a91 --lines 120-180    # just that range
rtok expand 7f3a91 --grep "panicked"  # just the matches, as N:line (regex)
rtok expand 7f3a91 --grep "panicked" --context 3  # matches with 3 lines around each, windows merged with --
rtok expand 7f3a91 --lines 210-230    # then the lines around hit 214
```

With the hooks installed this happens on its own: `PreToolUse` rewrites the agent's Bash
call to `rtok run -- <command>`.

### Ask the code graph instead of grepping

```bash
rtok graph index .
```

```text
indexed 5 files · 551 rows · 0 skipped · 5 read
```

The agent then reaches it over MCP as `symbol`, `callers`, `impact`, `outline`, `explore`,
`graph_diff` and `graph_export` — seven tools whose descriptions cost 165 tokens, in place of a grep-and-read chain. Each takes an
optional `project` (id or directory); without it a call answers for the working directory's
project and the projects it links to (`rtok graph projects link`). Definition
lookups are exact (recall and precision 1.000 over a hand-labelled set); reference lookups
find about a third of the sites, which [docs/comparison.md](comparison.md) explains
rather than hides.

### See what is on and where it runs

```bash
rtok plugins
```

```text
id        enabled   surfaces
measure   on        cli,proxy
cmd       on        hook,cli
read      on        mcp,hook
json_tree off       proxy,mcp
archive   on        proxy,mcp,cli
proxy     on        proxy
inject    on        hook
guard     on        hook,cli
memory    on        mcp,hook
graph     on        mcp,hook
toon      on        proxy,mcp
compress  on        proxy
```

Turn one off with `rtok config set plugins.cmd.enabled false`, or turn `toon` off with
`rtok config set plugins.toon.enabled false` — same thing as a `[plugins.<id>]` table in the
config file.

### Find out where a setting came from

Every flag is a config key, and every key knows its layer:

```bash
rtok config show --sources
```

```text
core.db_path = ~/.rtok/rtok.db (user)
log.level = info (user)
web.port = 3333 (user)
estimator.code = 3.5 (user)
…
```

`rtok proxy --port 8791` and `[proxy] port = 8791` are the same setting reached two ways;
`--sources` says which one won.

### Send the ledger to your observability stack

```bash
rtok otel status
```

```text
endpoint: none (set [otel] endpoint or OTEL_EXPORTER_OTLP_ENDPOINT)
calls: mark 0 · 0 pending
logs: mark 0 · 0 pending
sessions: mark 0
```

Point `[otel] endpoint` at Jaeger, Grafana, SigNoz or Maple and `rtok otel flush` posts
calls, logs and metrics as OTLP/HTTP JSON — see [`docs/otel.md`](otel.md).
