# RenderScope 0.2.0 — Forge / Minecraft 1.20.1

## Установка

1. Используйте **Minecraft Java 1.20.1**, **Forge 47.4.10** и **Java 21**.
2. Поместите [renderscope-forge-0.2.0+mc1.20.1.jar](renderscope-forge-0.2.0+mc1.20.1.jar) в клиентскую папку `mods` соответствующего профиля. Уберите из неё предыдущий RenderScope JAR: в профиле должен быть один файл мода для его загрузчика.
3. Загрузите мир, дайте профилировщику несколько секунд и нажмите **F8** или **Esc → RenderScope**.

MixinExtras включён в основной JAR. Файлы `-slim`, `-sources` и `-compat-harness` для обычной установки не используются. **Установка на сервер не требуется.** Клавиша меняется через **Настройки → Управление → RenderScope**.

В **Mods** выберите мод, в **Objects** — тип и экземпляр. **Highlight** подсвечивает его, **Track** сохраняет отслеживание, **Copy Position** копирует позицию. **Heatmap** и **Inspect** включаются в интерфейсе.

По умолчанию работает **Timing Only**: CPU-side renderer/visual callbacks с раздельными источниками. **Full** добавляет наблюдаемые BufferBuilder vertices/RenderType и живую асинхронную GPU-диагностику общих Flywheel batches на поддерживаемом контексте. Общие CPU/GPU-фазы не распределяются между объектами; per-object instanced geometry, GPU time и VRAM не вычисляются. CPU sources и shared phases входят в JSON, живая GPU-сводка — нет.

Для отчёта запустите **Capture**, завершите запись и нажмите **Export JSON**. Файл находится в `renderscope-reports` игрового профиля. Координаты и dimension выключены по умолчанию; имена игроков, серверы, чат, UUID и абсолютные пути не экспортируются.

## Проверенный артефакт

Основной JAR: SHA-256 **`1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`**; текущая сборка проходит **36 unit/privacy-тестов**. LICENSE: SHA-256 `cf2bc5c60fc35b9f87e8c001367cd7073a52c5604daf5a93437798900d1a94b6`. Лицензия и сторонние уведомления внутри JAR и обоих дистрибутивов побайтно совпадают с исходными текстами.

Этот JAR прошёл установленный Forge/Create/Mekanism сценарий **2026-10-20T21:17:02.977824300Z**: **593 кадров / 5.0061445 с**, 11 игровых скриншотов. Vanilla baseline от **2026-10-20T20:50:33.028882800Z** сохранён отдельно: 82 production .class совпадают с новым JAR, изменились только юридические metadata/тексты.

## Материалы

- [LICENSE](LICENSE) — RenderScope Source-Available License 1.0; [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — отдельные условия сторонних компонентов.
- [MODRINTH.md](MODRINTH.md), [CURSEFORGE.md](CURSEFORGE.md), [DESCRIPTION_RU.md](DESCRIPTION_RU.md) — локальные описания 0.2.0.
- `brand/` — оригинальное лого и точные целочисленные увеличения без добавленных полей.
- `gallery/` — игровые скриншоты и GIF.
- [verification.json](verification.json) — текущий SHA-256 JAR и точная область подтверждённых проверок.
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) — список проведённых и оставшихся проверок.

Forge **0.2.0** загружен как [Alpha в Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu) и [Alpha в CurseForge, файл 9271697](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271697). Оба повторно скачанных Forge JAR — 371 686 байт, SHA-256 `1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`, совпадают с installed-артефактом. Отдельный NeoForge 0.2.0 также загружен в [Modrinth, версия 41g9JyXE](https://modrinth.com/mod/renderscope/version/41g9JyXE) и [CurseForge, файл 9271865](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271865); его JAR — 371 691 байт, SHA-256 `2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`. Modrinth остаётся **«На рассмотрении»** по исходной заявке проекта от **21 октября 00:37 МСК**: добавление NeoForge не создавало повторную заявку. Оба CF-файла **Under Review**, с **Client** на уровне файла и отдельным правильным loader. Публичность после одобрения разрешена; у обоих CF-файлов выбрана автоматическая публикация после approval. **Одобрение и состоявшаяся публичная публикация пока не подтверждены.** Исторические Private/no-JAR и прежние Forge-only snapshots сохранены; Forge installed-сцена не считается проверкой NeoForge.

Публичные исходники: [AlexFirst404/RenderScope](https://github.com/AlexFirst404/RenderScope). Собственная **source-available** лицензия разрешает изучение, изменения и включение в бесплатные проекты и модпаки с сохранением лицензии и уведомлений. Продажа, платный доступ и включение в платные проекты, подписки или модпаки требуют предварительного письменного разрешения правообладателя. Публиковать личные изменения не требуется. Это не MIT и не OSI-approved open source; полный английский текст [LICENSE](LICENSE) определяет условия.

## English

Use **Minecraft 1.20.1**, **Forge 47.4.10** and **Java 21**. Put `renderscope-forge-0.2.0+mc1.20.1.jar` in your client profile's `mods` folder, with one RenderScope JAR matching that loader. MixinExtras is bundled. Open a world, then press **F8** or **Esc → RenderScope**. The server does not need RenderScope.

Renderer and supported Flywheel visual CPU work is measured per instance. Full mode adds observed geometry and asynchronous shared Flywheel batch GPU diagnostics; no per-object GPU time or instanced vertex allocation is invented. JSON exports include CPU sources/shared phases, with positions off by default. See `verification.json` for the exact tested artifact and scope.

RenderScope uses the custom **RenderScope Source-Available License 1.0**. Modification and inclusion in freely available projects/modpacks are allowed subject to its notices and conditions. Selling it, charging for access, or including it in paid projects/modpacks requires prior written permission. See [LICENSE](LICENSE); third-party components retain their own licenses.

The [GitHub repository](https://github.com/AlexFirst404/RenderScope) is Public. Separate Forge and NeoForge 0.2.0 Alpha files are uploaded to both platforms: [Forge on Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu), [NeoForge on Modrinth](https://modrinth.com/mod/renderscope/version/41g9JyXE), [Forge on CurseForge](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271697), and [NeoForge on CurseForge](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271865). Each downloaded JAR matches its corresponding local artifact. Modrinth remains the same **Under Review** project submitted at **00:37 Europe/Moscow on October 21**; adding NeoForge did not create another moderation submission. Both CurseForge files remain **Under Review**, with **Client** saved at file level and only the matching loader selected. Public release is authorized after approval, which has not yet been verified. The installed Create/Mekanism scene is Forge evidence; NeoForge retains its separate vanilla baseline.
