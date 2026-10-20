# RenderScope 0.2.0 — NeoForge / Minecraft 1.20.1

## Установка

1. Используйте **Minecraft Java 1.20.1**, **NeoForge 47.1.106** и **Java 21**.
2. Поместите [renderscope-neoforge-0.2.0+mc1.20.1.jar](renderscope-neoforge-0.2.0+mc1.20.1.jar) в клиентскую папку `mods` соответствующего профиля. Уберите из неё предыдущий RenderScope JAR: в профиле должен быть один файл мода для его загрузчика.
3. Загрузите мир, дайте профилировщику несколько секунд и нажмите **F8** или **Esc → RenderScope**.

MixinExtras включён в основной JAR. Файлы `-slim`, `-sources` и `-compat-harness` для обычной установки не используются. **Установка на сервер не требуется.** Клавиша меняется через **Настройки → Управление → RenderScope**.

В **Mods** выберите мод, в **Objects** — тип и экземпляр. **Highlight** подсвечивает его, **Track** сохраняет отслеживание, **Copy Position** копирует позицию. **Heatmap** и **Inspect** включаются в интерфейсе.

По умолчанию работает **Timing Only**: CPU-side renderer/visual callbacks с раздельными источниками. **Full** добавляет наблюдаемые BufferBuilder vertices/RenderType и живую асинхронную GPU-диагностику общих Flywheel batches на поддерживаемом контексте. Общие CPU/GPU-фазы не распределяются между объектами; per-object instanced geometry, GPU time и VRAM не вычисляются. CPU sources и shared phases входят в JSON, живая GPU-сводка — нет.

Для отчёта запустите **Capture**, завершите запись и нажмите **Export JSON**. Файл находится в `renderscope-reports` игрового профиля. Координаты и dimension выключены по умолчанию; имена игроков, серверы, чат, UUID и абсолютные пути не экспортируются.

## Материалы

- [MODRINTH.md](MODRINTH.md), [CURSEFORGE.md](CURSEFORGE.md), [DESCRIPTION_RU.md](DESCRIPTION_RU.md) — локальные описания 0.2.0.
- `brand/` — оригинальное лого и точные целочисленные увеличения без добавленных полей.
- `gallery/` — игровые скриншоты и GIF.
- [verification.json](verification.json) — текущий SHA-256 JAR и точная область подтверждённых проверок.
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) — список проведённых и оставшихся проверок.

0.2.0 на Modrinth/CurseForge не загружалась. Историческая Private-версия Modrinth 0.1.0 сохранена отдельно; CurseForge остаётся авторским черновиком без JAR. Локальные файлы и ZIP не означают публикацию.

## English

Use **Minecraft 1.20.1**, **NeoForge 47.1.106** and **Java 21**. Put `renderscope-neoforge-0.2.0+mc1.20.1.jar` in your client profile's `mods` folder, with one RenderScope JAR matching that loader. MixinExtras is bundled. Open a world, then press **F8** or **Esc → RenderScope**. The server does not need RenderScope.

Renderer and supported Flywheel visual CPU work is measured per instance. Full mode adds observed geometry and asynchronous shared Flywheel batch GPU diagnostics; no per-object GPU time or instanced vertex allocation is invented. JSON exports include CPU sources/shared phases, with positions off by default. See `verification.json` for the exact tested artifact and scope.
