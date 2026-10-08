---
lang: ru
---

# Задачи

rtok ведёт один пронумерованный план на проект. Каждый checkout, worktree и агент проекта берёт номера задач из одного счётчика, поэтому два агента не выбирают один и тот же id. Здесь описаны команды задач и MCP-инструменты; ключи конфигурации — в [config.md](config.md), а id агентов и worktree, с которыми работают агенты, — в [agents-and-worktrees.md](agents-and-worktrees.md).

## Id

- Id верхнего уровня — префикс из ASCII-букв и число, например `A12`. Подзадача добавляет одно число через точку: `A12.3`.
- Глубина два уровня. У задачи могут быть подзадачи; у подзадачи своих подзадач быть не может.
- Префикс задаётся в `[tasks] prefix`, от 1 до 8 букв. Если он пустой, rtok берёт первую букву имени проекта в верхнем регистре (`rtok` даёт `R`).
- Ввод не чувствителен к регистру. Вывод — в верхнем регистре.
- Id берутся из счётчика проекта в хранилище rtok. Перед каждым новым id счётчик поднимается выше наибольшего id, который уже есть у адаптера, поэтому задача, пришедшая через pull, не получит повторный номер. Счётчик учитывает id с любым префиксом, так что смена префикса не сбрасывает нумерацию. Номера подзадач считаются отдельно для каждого родителя.

## Статусы

| Статус | Значение |
| --- | --- |
| `open` | в плане, не начата |
| `in-progress` | в плане, в работе |
| `done` | завершена; выходит из плана |
| `closed` | не будем делать; выходит из плана |

- `open` и `in-progress` — активные. `list` показывает только активные задачи, если не указан `--all`, который добавляет завершённые.
- Родитель с активными подзадачами завершить нельзя. Перевод в `done` или `closed` отклоняется, а ошибка перечисляет подзадачи, если не указан `--force`.
- Парсер также принимает слова, которыми часто пользуются агенты: `todo` и `pending` вместо `open`, `completed` вместо `done`, `wontdo`, `won't-do` и `not-planned` вместо `closed`. Подчёркивание вместо дефиса тоже работает, так что `in_progress` принимается.

## Адаптер disk

Адаптер `disk` используется по умолчанию. Каждая задача — один Markdown-файл в каталоге `tasks` в корне checkout:

```text
tasks/A12 - ship-the-thing.md
tasks/done/A7 - old-work.md
```

- Имя файла — `<id> - <slug>.md`. Slug — строчные латинские буквы и цифры, соединённые дефисами, не длиннее 48 символов. Заголовок в имени не хранится, он в front matter.
- Front matter содержит `id`, `title`, `status`, `parent` (только у подзадач), `created_at` и `updated_at` (Unix-секунды). Описание — тело файла под front matter.
- Файлы относятся к тому checkout, в котором запущена команда. Номера общие через хранилище, но worktree читает и пишет файлы задач на своей ветке, поэтому они попадают в другие checkout через git.
- Завершённые и закрытые задачи переносятся в `tasks/done/`, так что план — это листинг каталога.
- Запись идёт во временный файл, который затем переименовывается на место.
- Файлы, не совпадающие с шаблоном `<id> - <slug>.md`, игнорируются, поэтому README рядом с задачами не мешает.

Пример файла задачи:

```markdown
---
id: A12
title: Ship the thing
status: open
created_at: 1790000000
updated_at: 1790000000
---

Why the task exists and what done means.
```

## Команды

| Команда | Что делает |
| --- | --- |
| `rtok task init [--adapter disk\|github\|gitlab] [--prefix <letters>]` | записывает `[tasks]` в `.rtok.toml` checkout и печатает следующий id; префикс по умолчанию — первая буква имени проекта |
| `rtok task create <title> [-d <text> \| --body-file <path\|->] [--parent <id>] [--json]` | добавляет задачу под следующий свободный id (`A12`, или `A12.3` при `--parent`); `--body-file -` читает описание из stdin |
| `rtok task list [--status <s,…>] [--all] [--parent <id>] [--json]` | активные задачи, подзадачи выводятся с отступом под родителем; `--status` принимает список через запятую |
| `rtok task show <id> [--json]` | одна задача с подзадачами, ссылкой и описанием |
| `rtok task status <id> [<status>] [--force] [--json]` | читает статус или задаёт `open`, `in-progress`, `done` или `closed` |
| `rtok task next [--json]` | открытая задача с наименьшим номером, у которой нет активных (открытых или в работе) подзадач |

Без `--json` команды печатают текст. С `--json` печатают задачу как JSON.

## MCP-инструменты

`rtok mcp` отдаёт те же операции как инструменты. Каждый возвращает тот же JSON, что и соответствующий флаг `--json`.

| MCP-инструмент | Аргументы | То же, что |
| --- | --- | --- |
| `task_create` | `title` (обязательный), `description`, `parent` | `task create` |
| `task_list` | `status` (список или строка через запятую), `all`, `parent` | `task list` |
| `task_get` | `id` (обязательный) | `task show` |
| `task_status` | `id` (обязательный), `status`, `force` | `task status` |
| `task_next` | нет | `task next` |

## Конфигурация

Задайте `[tasks]` для каждого проекта в `.rtok.toml`; `rtok task init` записывает его туда. Ключи и значения по умолчанию взяты из `config/default.toml`:

```toml
[tasks]                               # task adapters; usually set per project in .rtok.toml
# adapter = "disk"                    # disk | github | gitlab
# prefix  = ""                        # task id prefix (R → R12); empty: first letter of the project name

[tasks.disk]
# dir = "tasks"                       # one Markdown file per task, relative to the project root; done ones go to <dir>/done

[tasks.github]
# repo    = ""                        # owner/name; empty: the origin remote
# project = 0                         # Projects v2 number whose Status field tracks tasks; 0 = issues only

[tasks.gitlab]
# url     = "https://gitlab.com"      # base URL; set it for a self-hosted instance
# project = ""                        # group/name or numeric id; empty: the origin remote
```

Адаптеры GitHub и GitLab ещё не собраны (T441.7 и T441.8). До этого команда задач с `adapter = "github"` или `"gitlab"` завершается ошибкой, которая это сообщает. Их разделы появятся на этой странице, когда они будут готовы.
