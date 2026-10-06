---
lang: uk
---

# Colima (контейнери без Docker Desktop)

Агентам і локальним скриптам слід використовувати цей **закріплений через mise** стек замість Docker Desktop.
Він ~сумісний із Docker (Docker Engine API через Colima). Це **не** Podman.

## Стек

```
docker / docker compose  →  Colima (Docker runtime)  →  Lima VM
```

| Складник | Роль |
| --- | --- |
| `docker-cli` | клієнт `docker` |
| `docker-compose` | окремий бінарник `docker-compose` (Compose v5); необов'язковий cli-plugin для `docker compose` |
| `colima` | запускає й зупиняє сумісний із Docker демон усередині VM |
| `lima` | бекенд VM, яким керує Colima (`limactl` напряму ви викликаєте рідко) |

Закріплені версії зберігаються в `mise.toml` (`colima` 0.10.3, `lima` 2.2.0, `docker-cli` 29.8.1, `docker-compose` 5.5.1). Colima і Lima встановлюються лише на Linux і macOS.

## Встановлення

З кореня репозиторію:

```bash
mise install
```

Або встановіть лише стек: `mise install colima lima docker-cli docker-compose`.

mise встановлює Compose як бінарник **`docker-compose`**. Цього достатньо для скриптів і агентів.
Щоб отримати ще й підкоманду `docker compose`, один раз додайте посилання на плагін:

```bash
mkdir -p ~/.docker/cli-plugins
ln -sf "$(mise which docker-compose)" ~/.docker/cli-plugins/docker-compose
```

## Запуск і перевірка

```bash
colima start
docker context ls          # очікується контекст `colima`
docker context use colima  # якщо він ще не поточний
docker ps                  # має звертатися до демона Colima
```

Агенти мають надавати перевагу `docker` / `docker-compose` (або `docker compose`, якщо додано посилання на cli-plugin) поверх Colima. **Не** вимагайте Docker Desktop і не кажіть користувачеві встановлювати його заради rtok.

## Скрипти, що викликають `docker run`

Рецепти в `docs/otel.md` (Jaeger, Grafana `otel-lgtm`, SigNoz compose) і будь-які однорядкові команди `docker run` / `docker compose` / `docker-compose` працюють без змін, **щойно Colima запущено** і активний контекст Docker указує на нього.

## Зупинка

```bash
colima stop
```

## Усунення несправностей

| Симптом | Виправлення |
| --- | --- |
| `Cannot connect to the Docker daemon` / демон не запущено | `colima start`, потім `docker context use colima` |
| `docker: command not found` | `mise install` (або `mise exec -- docker …`) |
| `colima: command not found` | те саме — закріплені версії в `mise.toml` |
| Не той рушій / досі вибрано Desktop | `docker context ls` і `docker context use colima` |

## Примітка щодо сумісності

Цей стек розрахований на **Docker CLI + Compose**, що звертаються до Docker-середовища виконання Colima. Не підміняйте його Podman і не розраховуйте на rootless-сокети Podman, якщо окреме завдання цього не вимагає.
