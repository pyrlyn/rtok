---
lang: uk
---

# Завдання

rtok веде один пронумерований план на проєкт. Кожен checkout, worktree й агент проєкту бере номери завдань з одного лічильника, тому два агенти не обирають той самий id. Тут описано команди завдань і MCP-інструменти; ключі конфігурації — у [config.md](config.md), а id агентів і worktree, з якими працюють агенти, — у [agents-and-worktrees.md](agents-and-worktrees.md).

## Id

- Id верхнього рівня — префікс з ASCII-літер і число, наприклад `A12`. Підзавдання додає одне число через крапку: `A12.3`.
- Глибина — два рівні. У завдання можуть бути підзавдання; у підзавдання своїх підзавдань бути не може.
- Префікс задається в `[tasks] prefix`, від 1 до 8 літер. Якщо він порожній, rtok бере першу літеру назви проєкту у верхньому регістрі (`rtok` дає `R`).
- Введення не чутливе до регістру. Виведення — у верхньому регістрі.
- Id беруться з лічильника проєкту в сховищі rtok. Перед кожним новим id лічильник піднімається вище за найбільший id, який уже є в адаптера, тож завдання, що прийшло через pull, не отримає повторний номер. Лічильник враховує id з будь-яким префіксом, тому зміна префікса не скидає нумерацію. Номери підзавдань рахуються окремо для кожного батька.

## Статуси

| Статус | Значення |
| --- | --- |
| `open` | у плані, не розпочато |
| `in-progress` | у плані, в роботі |
| `done` | завершено; виходить з плану |
| `closed` | не будемо робити; виходить з плану |

- `open` і `in-progress` — активні. `list` показує лише активні завдання, якщо не вказано `--all`, який додає завершені.
- Батька з активними підзавданнями завершити не можна. Перехід у `done` або `closed` відхиляється, а помилка перелічує підзавдання, якщо не вказано `--force`.
- Парсер також приймає слова, якими часто користуються агенти: `todo` і `pending` замість `open`, `completed` замість `done`, `wontdo`, `won't-do` і `not-planned` замість `closed`. Підкреслення замість дефіса теж працює, тож `in_progress` приймається.

## Адаптер disk

Адаптер `disk` використовується за замовчуванням. Кожне завдання — один Markdown-файл у каталозі `tasks` у корені checkout:

```text
tasks/A12 - ship-the-thing.md
tasks/done/A7 - old-work.md
```

- Ім'я файла — `<id> - <slug>.md`. Slug — рядкові латинські літери та цифри, з'єднані дефісами, не довший за 48 символів. Заголовок у імені не зберігається, він у front matter.
- Front matter містить `id`, `title`, `status`, `parent` (лише для підзавдань), `created_at` і `updated_at` (Unix-секунди). Захоплення додає `assignee`, `blocked_by` і `priority` (0–4, поле опускається, коли воно дорівнює 2). Файли, записані до цих ключів, і далі читаються. Опис — тіло файла під front matter.
- Файли належать тому checkout, у якому запущено команду. Номери спільні через сховище, але worktree читає й записує файли завдань на своїй гілці, тому вони потрапляють в інші checkout через git.
- Завершені та закриті завдання переносяться до `tasks/done/`, тож план — це лістинг каталогу.
- Запис іде у тимчасовий файл, який потім перейменовується на місце.
- Файли, що не відповідають шаблону `<id> - <slug>.md`, ігноруються, тож README поруч із завданнями не заважає.

Приклад файла завдання:

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

## Команди

| Команда | Що робить |
| --- | --- |
| `rtok task init [--adapter disk\|github\|gitlab] [--prefix <letters>]` | записує `[tasks]` у `.rtok.toml` checkout і друкує наступний id; префікс за замовчуванням — перша літера назви проєкту |
| `rtok task create <title> [-d <text> \| --body-file <path\|->] [--parent <id>] [--json]` | додає завдання під наступний вільний id (`A12`, або `A12.3` із `--parent`); `--body-file -` читає опис із stdin |
| `rtok task list [--status <s,…>] [--all] [--parent <id>] [--json]` | активні завдання, підзавдання виводяться з відступом під батьком; `--status` приймає список через кому |
| `rtok task show <id> [--json]` | одне завдання з підзавданнями, посиланням і описом |
| `rtok task status <id> [<status>] [--force] [--json]` | читає статус або встановлює `open`, `in-progress`, `done` чи `closed` |
| `rtok task next [--json]` | перше готове завдання. Якщо блокерів немає, це й далі відкрите завдання з найменшим номером без активного підзавдання |
| `rtok task ready [--json]` | усі завдання, які можна взяти, спочатку з вищим пріоритетом (`0` раніше за `2`) |
| `rtok task claim [id] [--agent <id>] [--json]` | взяти `id` або перше готове завдання. Потрібен `--agent` або `RTOK_AGENT_ID`. У GitHub і GitLab запис виконавця — остання зміна перемагає, це не compare-and-set |
| `rtok task release <id> [--agent <id>] [--force] [--json]` | зняти виконавця і повернути статус `open`. Лише власник, якщо не вказано `--force` |
| `rtok task dep <id> <blocker> [--json]` | `id` чекає, доки `blocker` не стане `done` або `closed`. Цикл відхиляється, і нічого не записується |
| `rtok task priority <id> <0-4> [--json]` | задати пріоритет. `0` — найвищий, `2` — типове значення, воно не зберігається |

Без `--json` команди друкують текст. З `--json` друкують завдання як JSON.

## MCP-інструменти

`rtok mcp` віддає ті самі операції як інструменти. Кожен повертає той самий JSON, що й відповідний прапорець `--json`.

| MCP-інструмент | Аргументи | Те саме, що |
| --- | --- | --- |
| `task_create` | `title` (обов'язковий), `description`, `parent` | `task create` |
| `task_list` | `status` (список або рядок через кому), `all`, `parent` | `task list` |
| `task_get` | `id` (обов'язковий) | `task show` |
| `task_status` | `id` (обов'язковий), `status`, `force` | `task status` |
| `task_next` | немає | `task next` |
| `task_ready` | немає | `task ready` |
| `task_claim` | `id`, `agent` | `task claim` |
| `task_release` | `id` (обов'язковий), `agent`, `force` | `task release` |
| `task_dep` | `id` (обов'язковий), `blocker` (обов'язковий) | `task dep` |
| `task_priority` | `id` (обов'язковий), `level` (обов'язковий, 0–4) | `task priority` |

## Конфігурація

Задайте `[tasks]` для кожного проєкту в `.rtok.toml`; `rtok task init` записує його туди. Ключі та значення за замовчуванням узято з `crates/rtok-config/default.toml`:

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

Адаптери GitHub і GitLab ще не зібрано (T441.7 та T441.8). До того часу команда завдань з `adapter = "github"` або `"gitlab"` завершується помилкою, яка це повідомляє. Їхні розділи з'являться на цій сторінці, коли вони будуть готові.
