---
lang: ru
---

# Плагины

Каждый метод сокращения токенов в rtok — это плагин за одним трейтом. Плагины — встроенные
модули за фичами Cargo: никакого демона, никаких подпроцессов, никакого WASM в v0.1.

Столбец **заменяет** — это цель спецификации, а не зависимость: rtok реализует
поведение заново, с нуля, и никогда не запускает, не линкует и не читает названный инструмент.

| Плагин | Заменяет (только спецификация) | Интерфейс |
|--------|----------------------|---------|
| [`measure`](../../src/plugins/measure/README.md) | rtk gain, headroom savings, lean-ctx gain | `stats`, `bench`, прокси |
| [`cmd`](../../src/plugins/cmd/README.md) | rtk hook, ctx_shell, bash_compress | PreToolUse(Bash) → `rtok run` |
| [`read`](../../src/plugins/read/README.md) | lean-ctx read/search/tree, read_cache | MCP `read` |
| [`json_tree`](../../src/plugins/json_tree/README.md) | — | proxy, MCP |
| [`archive`](../../src/plugins/archive/README.md) | CCR | `expand`, хранилище |
| [`proxy`](../../src/plugins/proxy/README.md) | caveman-proxy | `ANTHROPIC_BASE_URL`, `OPENAI_BASE_URL` |
| [`inject`](../../src/plugins/inject/README.md) | caveman/ponytail, lean-ctx | SessionStart, UserPromptSubmit |
| [`guard`](../../src/plugins/guard/README.md) | — | PreToolUse |
| [`memory`](../../src/plugins/memory/README.md) | claude-mem | MCP, PreCompact |
| [`graph`](../../src/plugins/graph/README.md) | codebase-memory-mcp | MCP |
| [`toon`](../../src/plugins/toon/README.md) | — | MCP |

## Отключение плагинов

Во время работы, в `~/.rtok/config.toml`:

```toml
[plugins.cmd]
enabled = false
```

Или при сборке, чтобы код вообще не компилировался:

```bash
cargo build --no-default-features --features cmd,read
```

## Каталог каждого плагина

| Файл | Что содержит |
|------|-------|
| `README.md` | что делает плагин и зачем — на него ссылается таблица выше |
| `AGENTS.md` | инварианты и правила задач для тех, кто над ним работает |
| `PLAN.md` | собственный план разработки плагина |

Как написать свой: [написание плагинов](plugin-authoring.md).

## Формат экспорта графа

`rtok graph export` и MCP-инструмент `graph_export` выдают один JSON-документ, `"schema": "rtok.graph.v1"`, описанный
файлом [`docs/schemas/rtok.graph.v1.schema.json`](../schemas/rtok.graph.v1.schema.json) (он генерируется из типов
Rust и проверяется тестом). Ключи верхнего уровня:

| Ключ | Содержит |
|------|----------|
| `projects` | `id`, `name`, `root`, `origin`, `backend` (`tags`, `lsp` или `text`), `health` (`ok`, `stale`, `not indexed`, `missing`), `indexed_at` |
| `links` | `from`, `to`, `kind` (`manual` или `auto`), `reason`, `references` (ссылки-вызовы из `from` в `to`) |
| `nodes` | `id`, `project`, `kind`, `name`, `path` (относительно проекта), `line`; пуст на уровне `overview` |
| `edges` | `from`, `to` (id узлов), `kind` |
| `meta` | `scope`, `level` (`overview`, `symbols` или `focus`), `focus`, `depth`, `exported_at`, `rtok_version`, `redacted`, `partial`, `notes` |

Абсолютные пути, домашний каталог и имя пользователя скрываются, если не указан `--no-redact` (`graph_export` скрывает
всегда); исходный текст не включается никогда, имена файлов и символов включаются. Вызов, чьё имя имеет больше восьми
определений, ребра не рисует. `--from FILE` показывает сохранённый экспорт, не читая
и не меняя реестр и индекс.
