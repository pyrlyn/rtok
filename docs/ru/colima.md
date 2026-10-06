---
lang: ru
---

# Colima (контейнеры без Docker Desktop)

Агентам и локальным скриптам следует использовать этот **закреплённый в mise** стек вместо Docker Desktop.
Он ~совместим с Docker (Docker Engine API через Colima). Это **не** Podman.

## Стек

```
docker / docker compose  →  Colima (Docker runtime)  →  Lima VM
```

| Компонент | Роль |
| --- | --- |
| `docker-cli` | клиент `docker` |
| `docker-compose` | отдельный бинарник `docker-compose` (Compose v5); необязательный cli-плагин для `docker compose` |
| `colima` | запускает и останавливает Docker-совместимый демон внутри VM |
| `lima` | бэкенд VM, которым управляет Colima (`limactl` напрямую вызывать почти не приходится) |

Закреплённые версии находятся в `mise.toml` (`colima` 0.10.3, `lima` 2.2.0, `docker-cli` 29.8.1, `docker-compose` 5.5.1). Colima и Lima устанавливаются только в Linux и macOS.

## Установка

Из корня репозитория:

```bash
mise install
```

Или установите только стек: `mise install colima lima docker-cli docker-compose`.

mise устанавливает Compose как бинарник **`docker-compose`**. Этого достаточно для скриптов и агентов.
Чтобы получить ещё и подкоманду `docker compose`, один раз подключите плагин ссылкой:

```bash
mkdir -p ~/.docker/cli-plugins
ln -sf "$(mise which docker-compose)" ~/.docker/cli-plugins/docker-compose
```

## Запуск и проверка

```bash
colima start
docker context ls          # должен быть контекст `colima`
docker context use colima  # если он ещё не текущий
docker ps                  # должен обращаться к демону Colima
```

Агенты должны предпочитать `docker` / `docker-compose` (или `docker compose`, если cli-плагин подключён) поверх Colima. **Не** требуйте Docker Desktop и не предлагайте пользователю устанавливать его ради rtok.

## Скрипты, вызывающие `docker run`

Рецепты в `docs/otel.md` (Jaeger, Grafana `otel-lgtm`, SigNoz compose) и любые однострочники с `docker run` / `docker compose` / `docker-compose` работают без изменений, **как только Colima запущена** и активный контекст Docker указывает на неё.

## Остановка

```bash
colima stop
```

## Устранение неполадок

| Симптом | Решение |
| --- | --- |
| `Cannot connect to the Docker daemon` / демон не запущен | `colima start`, затем `docker context use colima` |
| `docker: command not found` | `mise install` (или `mise exec -- docker …`) |
| `colima: command not found` | то же самое — закреплённые версии в `mise.toml` |
| Не тот движок / всё ещё выбран Desktop | `docker context ls` и `docker context use colima` |

## Замечание о совместимости

Этот стек рассчитан на **Docker CLI + Compose**, обращающиеся к Docker-рантайму Colima. Не подменяйте его Podman и не рассчитывайте на сокеты rootless Podman, если отдельная задача этого не требует.
