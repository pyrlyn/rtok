---
lang: uk
---

# Плагіни

Кожен метод скорочення токенів у rtok — це плагін за одним трейтом. Плагіни — це вбудовані
модулі за Cargo features: жодного демона, жодних підпроцесів, жодного WASM у v0.1.

Стовпець **замінює** — це ціль специфікації, а не залежність: rtok заново реалізує
поведінку з нуля й ніколи не запускає, не лінкує й не читає названий інструмент.

| Плагін | Замінює (лише специфікація) | Інтерфейс |
|--------|----------------------|---------|
| [`measure`](../../src/plugins/measure/README.md) | rtk gain, headroom savings, lean-ctx gain | `stats`, `bench`, проксі |
| [`cmd`](../../src/plugins/cmd/README.md) | rtk hook, ctx_shell, bash_compress | PreToolUse(Bash) → `rtok run` |
| [`read`](../../src/plugins/read/README.md) | lean-ctx read/search/tree, read_cache | MCP `read` |
| [`archive`](../../src/plugins/archive/README.md) | CCR | `expand`, сховище |
| [`proxy`](../../src/plugins/proxy/README.md) | caveman-proxy | `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` |
| [`inject`](../../src/plugins/inject/README.md) | caveman/ponytail, lean-ctx | SessionStart, UserPromptSubmit |
| [`guard`](../../src/plugins/guard/README.md) | — | PreToolUse |
| [`memory`](../../src/plugins/memory/README.md) | claude-mem | MCP, PreCompact |
| [`graph`](../../src/plugins/graph/README.md) | codebase-memory-mcp | MCP |
| [`toon`](../../src/plugins/toon/README.md) | — | MCP |

## Вимкнення плагінів

Під час виконання, у `~/.rtok/config.toml`:

```toml
[plugins.cmd]
enabled = false
```

Або під час збирання, щоб код узагалі не компілювався:

```bash
cargo build --no-default-features --features cmd,read
```

## Каталог кожного плагіна

| Файл | Містить |
|------|-------|
| `README.md` | що робить плагін і навіщо — на нього посилається таблиця вище |
| `AGENTS.md` | інваріанти й правила задач для того, хто з ним працює |
| `PLAN.md` | власний план збирання плагіна |

Як написати власний: [створення плагінів](plugin-authoring.md).
