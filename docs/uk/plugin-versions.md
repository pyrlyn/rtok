---
lang: uk
---

# Версії плагінів і оновлення

Як rtok версіонує дерева плагінів у `plugins/<host>/`, записує, що він установив, і
вирішує, що `rtok agents update` робить із встановленим плагіном.

**Обсяг на сьогодні:** до рішення щодо версій підключено лише Claude Code. Codex, Copilot і Gemini
(і всі інші хости) мають файл версії, але зберігають свою попередню поведінку `agents update`;
вони приєднаються пізніше.

Кожен приклад нижче — це реальний запуск rtok 0.10.0 (або 0.17.0-alpha.3, де про це сказано
у виводі) з одноразовим `HOME` і заглушкою `claude` першою в `PATH` (справжній Claude Code
не задіяно). `--cli --no-restart` обмежують запуск
CLI Claude Code; кожен звіт скорочено до заголовка, рядків плагіна й помилок.

## Навіщо

Claude Code кешує плагін за `version` у його маніфесті. До rtok 0.10.0 кожен маніфест плагіна
вказував `0.0.1`, тож нова збірка rtok постачала плагін, який Claude уже мав «у цій
версії», і ніколи його не підхоплював. Сам rtok не мав запису про те, яку збірку плагіна встановлено і
звідки вона взялася, тож `agents update` міг лише щоразу перевстановлювати або довіряти хосту.

Плагін потрапляє на машину з одного з трьох джерел, і схема охоплює їх усі:

- **GitHub**: хост встановлює з репозиторію (`claude plugin marketplace add
  pyrlyn/rtok`).
- **Local**: хост встановлює з дерева плагіна на диску (`claude plugin marketplace add
  <path>/plugins/claude`) — для розробки й офлайн-встановлень.
- **Marketplace**: запис каталогу хоста для закоміченого `.claude-plugin/marketplace.json`.

## Файл версії

Кожне версіоноване дерево плагіна містить закомічений `plugins/<host>/.rtok-plugin-version` — один рядок
JSON:

```text
$ cat plugins/claude/.rtok-plugin-version
{"schema":1,"plugin":"claude","version":"0.10.0"}
```

| Поле | Значення |
| --- | --- |
| `schema` | Версія формату. rtok читає лише `1` і відхиляє будь-яке інше значення з помилкою, що називає файл. |
| `plugin` | Назва каталогу хоста (`claude`, `codex`, …). |
| `version` | SemVer; дорівнює версії з `Cargo.toml` у цьому коміті. Некоректна версія — помилка, що називає файл. |
| `source` | Необов'язкове: `local` у встановленій копії, яку записало локальне встановлення. Закомічені файли його ніколи не містять. |

Хости з файлом: `claude`, `codebuddy`, `codex`, `copilot`, `cursor`, `devin`, `gemini`, `grok`, `kimi`,
`opencode`, `pi`, `zcode`. `cline` не має власного маніфесту, а маніфест `antigravity` не має
поля версії, тож жоден із них файлу не має. Та сама версія записується в поле `version`
кожного маніфесту (`plugins/claude/.claude-plugin/plugin.json` та інші), тож хост, що кешує за
версією маніфесту, бачить кожен реліз як новий.

Ці файли записує лише реліз ([Випуск релізів](#випуск-релізів)). Файл лежить у корені плагіна,
тож кожне джерело копіює його разом із плагіном у встановлену копію хоста (для Claude — у його кеш
плагінів у `~/.claude/plugins/cache/rtok/`). `agents update` спершу читає встановлену версію
із цієї копії. Далі локальне встановлення переписує файл копії, записуючи в нього рядок збірки й
`"source":"local"` (rtok 0.17.0-alpha.3, з checkout із незакоміченими змінами):

```text
$ cat ~/.claude/plugins/cache/rtok/rtok/local/.rtok-plugin-version
{"schema":1,"plugin":"claude","version":"0.17.0-alpha.3+g3ccd3a28.dirty","source":"local"}
```

rtok ніколи не пише в checkout: коли Claude запускає плагін просто з локального дерева,
файл тієї копії не читається, і за неї говорить квитанція. `version` у маніфесті всередині
встановленої копії лишається таким, яким його закомічено.

## Квитанція `plugins.json`

rtok зберігає по одному рядку на хост про те, що він установив (сьогодні записує лише `claude`):

| ОС | Шлях |
| --- | --- |
| macOS | `~/Library/Application Support/rtok/plugins.json` |
| Linux | `$XDG_STATE_HOME/rtok/plugins.json`, інакше `~/.local/state/rtok/plugins.json` |
| Windows | `%LOCALAPPDATA%\rtok\plugins.json`, інакше `%USERPROFILE%\AppData\Local\rtok\plugins.json` |

Після локального встановлення з checkout із незакоміченими змінами (rtok 0.17.0-alpha.3; домашній шлях і
шлях checkout скорочено):

```json
{
  "claude": {
    "source": "local",
    "ref": "<checkout>/plugins/claude",
    "path": "~/.claude/plugins/cache/rtok/rtok/local",
    "version": "0.17.0-alpha.3+g3ccd3a28.dirty",
    "installed_at": "2026-10-21T01:55:49Z"
  }
}
```

Рядок GitHub натомість містить `"ref": "v<version>"` і `"marketplace": "rtok"`.

| Поле | Значення |
| --- | --- |
| `source` | `github`, `local` або `marketplace`. |
| `ref` | `v<version>` для GitHub і marketplace; шлях до локального дерева плагіна для локального встановлення. |
| `marketplace` | `rtok` для GitHub і marketplace; відсутнє для локального. |
| `path` | Встановлена копія: для Claude — `installPath` з `installed_plugins.json`, інакше `~/.claude/plugins/cache/rtok/rtok`. Рядки, записані до 0.17, називають сам `installed_plugins.json`; тоді пошук пропускає файл версії. |
| `version` | Встановлена версія; локальне встановлення додає `+g<sha>` або `+g<sha>.dirty`. |
| `installed_at` | UTC, RFC 3339, з точністю до секунди. |

Рядок записується після успішного встановлення, перевстановлення чи оновлення на місці й лишається без змін за
`--dry-run` і за будь-якого збою `claude`, який нічого не видалив. Його видаляють, коли перевстановлення
вже видалило старий плагін, а встановлення потім зазнало збою ([Рішення](#рішення)).
Відсутній файл читається як «немає рядків».

## Джерела

Для Claude `agents update` обирає джерело в такому порядку: `--source`, інакше `source` з рядка
квитанції, інакше GitHub.

| Джерело | Куди вказує marketplace `rtok` | Доступна версія |
| --- | --- | --- |
| GitHub | `pyrlyn/rtok` | власна версія запущеного rtok |
| Marketplace | `pyrlyn/rtok` | checkout каталогу Claude, `~/.claude/plugins/marketplaces/rtok/plugins/claude/.rtok-plugin-version`, читається після `claude plugin marketplace update rtok`; версія запущеного rtok, якщо файлу немає |
| Local | дерево плагіна, яке знаходить rtok (`plugins/claude` поруч із бінарником, у префіксі `share/rtok`, у сховищі ketch або у checkout вихідного коду) | `.rtok-plugin-version` цього дерева плюс `+g<sha>[.dirty]` з `git describe --always --dirty`; поза checkout git (`plugins/` встановленого rtok) — версія з файлу як є |

GitHub не потребує мережевого виклику: реліз тримає кожен файл версії рівним
`Cargo.toml`, тож тег `v<version>`, що відповідає бінарнику, несе саме цю версію. Marketplace
натомість іде за каталогом, який може випереджати бінарник: справжній `agents update` один раз оновлює його
перед читанням (і не оновлює вдруге для оновлення на місці); `--dry-run` читає
каталог у тому вигляді, в якому він лежить на диску. Локальне дерево читається з диска.

Встановлена версія береться з `.rtok-plugin-version` встановленої копії (каталог, який називає
`installPath` у `~/.claude/plugins/installed_plugins.json`, інакше тека кешу з назвою за `version`
запису), потім з рядка квитанції, потім з власного запису Claude
(`plugins["rtok@rtok"][0].version`), якщо це SemVer, інакше `0.0.0`. Файл версії, який
не розбирається, — помилка з його назвою; `--force` перевстановлює поверх нього. Вважається, що Claude
має плагін, коли `installed_plugins.json` містить `rtok@rtok`.

Запис `rtok` у `~/.claude/plugins/known_marketplaces.json` звіряється з вибраним
джерелом: `{"source":"github","repo":"pyrlyn/rtok"}` для GitHub і marketplace,
`{"source":"directory","path":"<local tree>"}` для локального. Будь-яке інше значення (наприклад, шлях до сховища ketch
до 0.10) застаріле й примушує до перевстановлення, яке перенаправляє його.

Репозиторій переїхав з облікового запису `listepo` до організації `pyrlyn`. Marketplace,
доданий до переїзду, досі записує `{"source":"github","repo":"listepo/rtok"}` для Claude або
`source = "https://github.com/listepo/rtok.git"` у `[marketplaces.rtok]` для Codex. Обидва
застарілі за правилом вище. Наступні `rtok agents install` або `agents update` видаляють
marketplace `rtok`, додають його знову з `pyrlyn/rtok` і один раз перевстановлюють `rtok@rtok`. Назви
marketplace і плагіна лишаються `rtok`, тож `rtok@rtok` і далі означає той самий плагін. Щоб перейти
вручну: `claude plugin marketplace remove rtok && claude plugin marketplace add
pyrlyn/rtok && claude plugin install rtok@rtok` (Codex: те саме з `codex plugin`, а
для останнього кроку `plugin add`).

## Рішення

Одна чиста функція (`decide` у `src/agents/plugin_version.rs`) порівнює встановлену й
доступну версії за пріоритетом SemVer, що ігнорує метадані збірки, а потім окремо порівнює метадані
збірки. Рядки перевіряються згори донизу; перемагає перший збіг.

| Випадок | Результат | Виклики `claude` |
| --- | --- | --- |
| Плагін не встановлено | встановлення | `plugin marketplace add` (якщо запис не актуальний), `plugin install rtok@rtok` |
| `--force` | перевстановлення | `plugin uninstall`, далі як вище |
| Джерело змінилося або запис marketplace застарів | перевстановлення | `plugin uninstall`, `plugin marketplace remove rtok` і `add` (якщо запис не актуальний), `plugin install` |
| Доступна новіша | оновлення на місці | `plugin marketplace update rtok`, `plugin update rtok@rtok` |
| Та сама базова версія, інші метадані збірки | оновлення на місці; перевстановлення для локального джерела | як вище; local: як для `--force` |
| Застаріле встановлення: немає квитанції, запис хоста `0.0.1` або не SemVer (`0.0.0`) | оновлення на місці (старіше за будь-який реліз) | як вище |
| Однакові версія й метадані збірки | пропуск | жодних |
| Доступна старіша | пропуск, попередження | жодних |

Якщо оновлення на місці зазнає збою, rtok переходить до перевстановлення й додає помилку. Нова
локальна збірка завжди перевстановлюється: базова версія в неї та сама, а Claude кешує за версією
маніфесту, тож `claude plugin update` залишив би стару копію.

Застаріле встановлення оновлюється один раз і записує квитанцію; повторний запуск не викликає жодної команди `claude`:

```text
$ rtok agents update claude --cli --no-restart
CLI: Claude Code
~ plugin rtok@rtok updated to 0.10.0
$ rtok agents update claude --cli --no-restart
CLI: Claude Code — already current
```

`--dry-run` показує рішення й нічого не записує. Оновлення чи пропуск називає версію;
встановлення чи перевстановлення виводить `would install|reinstall rtok@rtok from <source> v<version>`,
причину (`forced`, `source changed from … to …`, `stale marketplace`, `new local build`) і команди
`claude`, які було б виконано (другий запуск — rtok 0.17.0-alpha.3):

```text
$ rtok agents update claude --cli --no-restart --dry-run
CLI: Claude Code — dry run, nothing written
plugin rtok@rtok 0.10.0 up to date (github)
$ rtok agents update claude --cli --no-restart --dry-run --force
CLI: Claude Code — dry run, nothing written
would reinstall rtok@rtok from github v0.17.0-alpha.3 (forced): claude plugin uninstall rtok@rtok && claude plugin install rtok@rtok
```

Старіша доступна версія (квитанція на `0.11.0`, rtok на `0.10.0`) лишається на місці:

```text
$ rtok agents update claude --cli --no-restart
CLI: Claude Code
plugin rtok@rtok: available 0.10.0 is older than installed 0.11.0
```

`--force` перевстановлює незалежно від версій, зокрема з пониженням версії, і переписує квитанцію.
`--source github|local|marketplace` порівнює з цим джерелом замість записаного;
інше джерело означає перевстановлення, і квитанція перемикається на нього. Після цього новий локальний
коміт чи зміна у відстежуваному файлі змінює рядок збірки й викликає таке саме перевстановлення; та сама
збірка пропускається (rtok 0.17.0-alpha.3, шлях checkout скорочено):

```text
$ rtok agents update claude --cli --no-restart --dry-run --source local
CLI: Claude Code — dry run, nothing written
would reinstall rtok@rtok from local v0.17.0-alpha.3+g3ccd3a28.dirty (source changed from github to local): claude plugin uninstall rtok@rtok && claude plugin marketplace remove rtok && claude plugin marketplace add <checkout>/plugins/claude && claude plugin install rtok@rtok
$ rtok agents update claude --cli --no-restart --source local
CLI: Claude Code
+ plugin plugins/claude → rtok@rtok
```

**Коли перевстановлення зазнає збою.** Збій `claude` до того, як щось було видалено, зберігає старий плагін
і квитанцію, виводить рядок `offer … (claude failed: …)` і завершується з кодом 0. Якщо видалення
вдалося, а встановлення — ні, хост лишається без плагіна: rtok про це повідомляє, видаляє рядок квитанції
й завершується з ненульовим кодом. Наступний `agents update` бачить, що нічого не встановлено, і встановлює:

```text
$ rtok agents update claude --cli --no-restart --force
CLI: Claude Code
plugin rtok@rtok removed, reinstall failed: install failed
  ✗ plugin  not installed
  warning: plugin did not read back as installed
Error: a plugin reinstall failed
$ rtok agents update claude --cli --no-restart
CLI: Claude Code
+ plugin plugins/claude → rtok@rtok
```

## Перелік застарілих плагінів

`rtok agents outdated` перелічує хости, де плагін rtok старший за запущений rtok, і лише їх.
`rtok agents update --check` виводить точно те саме, для тих, хто шукає під `update`. Команда читає
лише локальні файли: без мережі, без виклику CLI хоста і без оновлення marketplace, тож
вона швидка й працює офлайн. Цільова версія — завжди власна версія запущеного бінарника
(`rtok --version`).

```console
$ rtok agents outdated
agent  installed available source
claude 0.0.1     0.14.0    github

run: rtok agents update claude
```

**Що перелічено.** Перевіряється кожен хост, який підтримує rtok (той самий реєстр, що й у
`agents list`), а не лише ті, що в квитанції, тож плагін, встановлений уручну або старішим rtok,
теж знайдеться. Варіант хоста потрапляє в рядок, коли там встановлено плагін і його версія
нижча за запущений rtok за пріоритетом SemVer. Метадані збірки ігноруються: локальна `0.14.0+g12c7e91`
на rtok `0.14.0` актуальна. Встановлену версію шукають у тому самому порядку, що й в
`agents update` ([рішення](#рішення)): файл `.rtok-plugin-version` у встановленій
копії, потім квитанція, потім власний запис хоста (`installed_plugins.json` Claude). Стовпець
`source` походить із того самого пошуку (`github`, `local`, `marketplace`).

**Що приховано.** Хости без плагіна, з тією самою версією та з новішою версією
не виводяться. Хост зі старішою версією, який встановлений, але версію якого ніщо не
записує (немає файлу версії, рядка квитанції чи придатного запису хоста), вважається `0.0.0` і показується як
`legacy`; хост, чий запис таки називає версію, як `0.0.1` вище, показує цю версію.

```console
$ rtok agents outdated
agent  installed available source
claude legacy    0.14.0    github

run: rtok agents update claude
```

**Нічого робити.** Два повідомлення, залежно від того, чи щось встановлено:

```console
$ rtok agents outdated
all rtok plugins are up to date (1 installed, rtok 0.14.0)
$ rtok agents outdated gemini
no rtok plugins installed
```

**Вибір хостів.** Як в `update`: необов'язковий список хостів через кому
(`rtok agents outdated claude,cursor`) і `--cli` / `--desktop` для одного варіанта.

**`--json`** виводить один об'єкт без людського повідомлення, також коли оновлювати нічого
(тоді `outdated` порожній, а `installed` рахує перевірені плагіни):

```console
$ rtok agents outdated --json
{"rtok":"0.14.0","outdated":[{"agent":"claude","variant":"cli","installed":"0.0.1","available":"0.14.0","source":"github","legacy":false}],"installed":1}
```

| Поле | Значення |
| --- | --- |
| `rtok` | Версія запущеного rtok, з якою порівнюється кожен рядок. |
| `outdated[].agent`, `.variant` | Id хоста й варіант (`cli` або `desktop`). |
| `outdated[].installed` | Встановлена версія або `legacy`, коли її ніщо не записує. |
| `outdated[].available` | Версія запущеного rtok. |
| `outdated[].source` | `github`, `local` або `marketplace`. |
| `outdated[].legacy` | `true` для рядка `legacy`. |
| `installed` | Скільки встановлень плагінів перевірено, застарілих чи ні. |

**`--exit-code`** завершується з кодом 10, коли застарів принаймні один хост, і з 0 в інших випадках. Без нього
код виходу в обох випадках 0, тож скрипт, який лише читає вивід, продовжує працювати:

```console
$ rtok agents outdated --json --exit-code; echo "exit=$?"
{"rtok":"0.14.0","outdated":[{"agent":"claude","variant":"cli","installed":"0.0.1","available":"0.14.0","source":"github","legacy":false}],"installed":1}
exit=10
```

**Чому це працює офлайн.** Доступна версія — це власна версія запущеного бінарника: тег або
запис каталогу, що відповідає цій збірці, несе той самий `.rtok-plugin-version`
(`tools/plugin-versions.sh --check` це гарантує), тож питати сервер нема в кого. Відрізнятися може лише
локальний checkout і оновлений каталог marketplace; їх читає `update`,
а `outdated` — ні.
Команда ніколи нічого не змінює: щоб діяти за списком, запустіть рядок `run:`, який вона виводить.

## Встановлена версія в `agents list`

Рядок `plugin` у `rtok agents list` і `rtok agents info <host>` називає встановлену версію та її
джерело; значення читається тим самим пошуком, що й у `agents outdated`. Якщо версія відрізняється
від запущеного rtok, рядок про це каже, а для старішої називає команду, яка це виправить;
встановлення, вік якого не визначають жодні файли, показується як `legacy`. `rtok web` показує
той самий текст на сторінці Hosts, старіший плагін позначений `outdated`.

```console
$ rtok agents list
CLI: Claude Code
  ✓ plugin  installed 0.14.0 (marketplace), rtok is 0.15.1 — rtok agents update claude
$ rtok agents list
CLI: Claude Code
  ✓ plugin  installed (legacy, no version)
```

З `--json` кожен варіант хоста несе `plugin_version` і `plugin_source`; обидва відсутні, якщо
плагін не встановлено або версію ніде не записано.

## Випуск релізів

`tools/plugin-versions.sh` — єдине місце, яке записує й перевіряє кожен файл версії та
маніфест:

```text
$ tools/plugin-versions.sh --set 0.10.1
$ cat plugins/claude/.rtok-plugin-version
{"schema":1,"plugin":"claude","version":"0.10.1"}
$ tools/plugin-versions.sh --check 0.10.1
$ echo $?
0
```

`--check <version>` виводить кожен файл, що відрізняється, і завершується з кодом 1; `--files` виводить кожен файл, якого
торкається скрипт.

- `tools/release.sh` (його запускають `just release` і `bump.yml`) викликає `--set` у тому самому
  коміті `release: v<version>`, що підвищує версію в `Cargo.toml` і `Cargo.lock`, і додає до індексу файли,
  які перелічує `--files`.
- `ci.yml` запускає `--check` проти версії з `Cargo.toml` на кожному пул-реквесті, що не є чернеткою, і
  кожному push у `main`.
- `release.yml` запускає `--check` проти тегу без `v` перед збиранням; розбіжність валить
  реліз.
- `tests/plugin_versions.rs` перевіряє, що кожен файл зі списку `--files` дорівнює `CARGO_PKG_VERSION`, тож
  `just check` виявляє розбіжність локально.

Ніщо інше не підвищує версію: release-plz більше не запускається в CI, а пул-реквест
воркфлоу Bump несе коміт `release.sh`, тож перевірка `ci.yml` виконується на ньому до злиття,
а перевірка `release.yml` усе одно зупиняє тег, що проскочив. Сам процес релізу див. у
[Випуску rtok](../release.md).

## Усунення несправностей

**Плагін лишається старим після `agents update`.** Спершу подивіться на рішення:

```text
$ rtok agents update claude --cli --no-restart --dry-run
CLI: Claude Code — dry run, nothing written
~ plugin rtok@rtok → 0.10.0 (claude plugin marketplace update rtok && claude plugin update rtok@rtok)
```

Якщо справжній запуск виводить `claude failed: claude not found on PATH`, нічого не змінилося, а
квитанція лишилася як була. Додайте `claude` у `PATH` і запустіть знову. Якщо пробний запуск
каже `up to date`, але копія, яку запускає Claude, стара, неправильний файл версії
встановленої копії або квитанція: `rtok agents update claude --force`.

**Застаріле встановлення.** Встановлення з часів до rtok 0.10.0 не має квитанції, а запис хоста —
`0.0.1` (або взагалі не SemVer). Перший `rtok agents update claude` оновлює його один раз і записує
квитанцію; більше нічого не потрібно. Якщо і це оновлення, і запасне перевстановлення зазнали збою після
видалення, див. «Коли перевстановлення зазнає збою» вище: повторно запустіть `rtok agents update claude`.

**Розбіжність версій у CI.** Крок `plugin-versions.sh --check` або `tests/plugin_versions.rs`
падає, коли маніфест чи файл версії відредаговано вручну:

```text
$ tools/plugin-versions.sh --check 0.10.1
plugins/gemini/gemini-extension.json: 0.0.2 (want 0.10.1)
$ echo $?
1
```

Перепишіть кожен файл за `Cargo.toml` і закомітьте результат:

```bash
tools/plugin-versions.sh --set "$(grep -m1 '^version = ' Cargo.toml | cut -d'"' -f2)"
```

**`--source local` зі встановленого rtok.** Локальне джерело читає метадані збірки з
`git describe`. `plugins/` встановленого rtok — не checkout git, тож його версія — це файл
дерева як його закомічено, без рядка збірки: встановлення працює офлайн, але пізніша збірка з
тією самою версією вважається актуальною. Використайте `--force` для перевстановлення або запустіть бінарник rtok,
зібраний із checkout. `--source local` падає, лише коли в знайденому дереві немає
`.rtok-plugin-version`; помилка називає файл.
