---
lang: ru
---

# LSP-бэкенд для `graph`

По умолчанию плагин `graph` отвечает на `symbol`, `callers`, `impact`, `outline` и
`explore` из своего индекса tree-sitter-tags в SQLite (`backend = "tags"`). Настройка
`backend = "lsp"` направляет те же пять MCP-инструментов — те же имена, те же вызывающие —
через языковой сервер, с которым общение идёт по stdio. Индекс tags при этом
для такого вызова не используется. Каждый ответ LSP записывает одну строку `Measurement` с
`plugin = "graph"` и `kind = "lsp.symbol" | "lsp.callers" | "lsp.impact" |
"lsp.outline" | "lsp.explore"`, поэтому `rtok stats` учитывает его как любую другую экономию.

Сервер выбирается по корню рабочей области — rtok никогда не линкует и не вызывает через оболочку
ничего другого (D6); он запускает один из этих серверов из `PATH`:

| Маркер корня | Команда сервера |
|-------------|----------------|
| `Cargo.toml` | `rust-analyzer` |
| `compile_commands.json` | `clangd` |
| `tsconfig.json` | `typescript-language-server --stdio` |
| `pubspec.yaml` | `dart language-server` |

Пошаговое описание ниже охватывает Rust и Dart.

## Rust: rust-analyzer

Установите один раз через rustup, затем проверьте, что бинарник отвечает:

```bash
rustup component add rust-analyzer
rust-analyzer --version
```

`rustup` кладёт `rust-analyzer` в `PATH`; кроме того, rtok находит его через
`rustup which rust-analyzer`, если rustup установлен.

## Dart: Dart SDK

Установите SDK (https://dart.dev/get-dart), затем проверьте, что он отвечает:

```bash
dart --version
```

Сервер — это собственная подкоманда SDK `language-server`: rtok запускает
`dart language-server`, поэтому никакого дополнительного шага установки, кроме самого SDK, нет.

## Как направить на него проект

Файл-маркер определяет корень рабочей области: `Cargo.toml` для крейта Rust,
`pubspec.yaml` для пакета Dart. Задайте бэкенд либо для проекта в
`<git root>/.rtok.toml`:

```toml
[plugins.graph]
backend = "lsp"
```

либо для отдельного вызова через окружение:

```bash
RTOK_PLUGINS_GRAPH_BACKEND=lsp rtok config get plugins.graph.backend
```

что печатает `lsp` (по умолчанию `tags`). С `--sources` строка выглядит как
`plugins.graph.backend = lsp (env)`:

```bash
rtok config show --sources | grep plugins.graph.backend
```

## Проверка работы

Вызовите MCP `outline` для файла-примера, затем `callers` для одного из его символов.
`tests/graph_lsp_gate.rs` — исполняемая версия этой проверки: он получает
`outline` файла `lib/main.dart` с двумя символами через `dart language-server`, проверяет,
что ссылка `OnlyTyped` находится через rust-analyzer (начиная с T52.5 она находится и через
tags; контрольный промах tags теперь — ссылка `macro_callee` в теле макроса),
и проверяет наличие одной строки измерения `lsp*`:

```bash
cargo test --test graph_lsp_gate
```

Все шесть тестов проходят, когда оба сервера есть в `PATH`; в противном случае тесты Dart и LSP
пропускаются.

## Без сервера

Если бинарника нет или выше запрашиваемого файла не найден файл-маркер,
инструмент возвращает ошибку вместо ответа — например,
`lsp: rust-analyzer not on PATH` или
`lsp: no Cargo.toml / compile_commands.json / tsconfig.json / pubspec.yaml in <root>`.
Ничего не индексируется взамен и ничего не записывается. Чтобы вернуться к
встроенному индексу, снова задайте `backend = "tags"` (значение по умолчанию); ответы tags
с выключенным флагом побайтно идентичны, согласно контрактному тесту `graph_lsp_gate`.
