---
lang: uk
---

# LSP-бекенд для `graph`

Типово плагін `graph` відповідає на `symbol`, `callers`, `impact`, `outline` і
`explore` зі свого індексу tree-sitter-tags у SQLite (`backend = "tags"`). Налаштування
`backend = "lsp"` спрямовує ті самі п'ять MCP-інструментів — ті самі назви, ті самі виклики —
натомість через мовний сервер, з яким rtok спілкується через stdio. Індекс tags тоді
для цього виклику не використовується. Кожна відповідь LSP записує один рядок `Measurement` з
`plugin = "graph"` і `kind = "lsp.symbol" | "lsp.callers" | "lsp.impact" |
"lsp.outline" | "lsp.explore"`, тож `rtok stats` враховує її як будь-яку іншу економію.

Кожен із п'яти інструментів приймає необов'язковий `project` (id або каталог). Без нього виклик
відповідає за проєктом робочого каталогу та пов'язаними з ним проєктами; мовний сервер відповідає
за один корінь, тому за `backend = "lsp"` відповідає лише перший проєкт цієї області, і відповідь
про це повідомляє.

Сервер обирається за коренем робочого простору — rtok ніколи не лінкує й не викликає через оболонку
нічого іншого (D6); він запускає один із цих серверів із `PATH`:

| Маркер кореня | Команда сервера |
|-------------|----------------|
| `Cargo.toml` | `rust-analyzer` |
| `compile_commands.json` | `clangd` |
| `tsconfig.json` | `typescript-language-server --stdio` |
| `pubspec.yaml` | `dart language-server` |

Покрокові інструкції нижче охоплюють Rust і Dart.

## Rust: rust-analyzer

Встановіть один раз через rustup, потім перевірте, що бінарник відповідає:

```bash
rustup component add rust-analyzer
rust-analyzer --version
```

`rustup` додає `rust-analyzer` у `PATH`; крім того, rtok розв'язує його через
`rustup which rust-analyzer`, якщо rustup є.

## Dart: Dart SDK

Встановіть SDK (https://dart.dev/get-dart), потім перевірте, що він відповідає:

```bash
dart --version
```

Сервер — це власна підкоманда SDK `language-server`: rtok запускає
`dart language-server`, тож жодного додаткового кроку встановлення, крім самого SDK, немає.

## Як налаштувати проєкт

Файл-маркер визначає корінь робочого простору: `Cargo.toml` для крейта Rust,
`pubspec.yaml` для пакета Dart. Задайте бекенд або для окремого проєкту в
`<git root>/.rtok.toml`:

```toml
[plugins.graph]
backend = "lsp"
```

або для окремого виклику через змінну середовища:

```bash
RTOK_PLUGINS_GRAPH_BACKEND=lsp rtok config get plugins.graph.backend
```

яка виводить `lsp` (типово — `tags`). З `--sources` рядок виглядає як
`plugins.graph.backend = lsp (env)`:

```bash
rtok config show --sources | grep plugins.graph.backend
```

## Як переконатися, що все працює

Викличте MCP `outline` для зразкового файлу, потім `callers` для одного з його символів.
`tests/graph_lsp_gate.rs` — це версія цієї перевірки, яку можна запустити: вона отримує
`outline` двосимвольного `lib/main.dart` через `dart language-server`, перевіряє,
що посилання `OnlyTyped` знаходиться через rust-analyzer (від T52.5 воно
знаходиться й через tags; закріплений промах для tags тепер — це посилання
`macro_callee` у тілі макроса), і перевіряє наявність одного рядка вимірювання `lsp*`:

```bash
cargo test --test graph_lsp_gate
```

Усі шість тестів проходять, коли обидва сервери є в `PATH`; інакше тести Dart і LSP
пропускаються.

## Без сервера

Коли бінарника немає або над запитаним файлом не знайдено жодного файлу-маркера,
інструмент повертає помилку замість відповіді — наприклад,
`lsp: rust-analyzer not on PATH` або
`lsp: no Cargo.toml / compile_commands.json / tsconfig.json / pubspec.yaml in <root>`.
Нічого не індексується на заміну й нічого не записується. Щоб повернутися до
вбудованого індексу, знову задайте `backend = "tags"` (типове значення); відповіді tags
побайтово ідентичні з вимкненим прапорцем, згідно з контрактним тестом `graph_lsp_gate`.
