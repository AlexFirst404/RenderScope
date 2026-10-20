# RenderScope 0.2.0 — NeoForge / Minecraft 1.20.1

## Установка

1. Используйте **Minecraft Java 1.20.1**, **NeoForge 47.1.106** и **Java 21**.
2. Поместите [renderscope-neoforge-0.2.0+mc1.20.1.jar](renderscope-neoforge-0.2.0+mc1.20.1.jar) в клиентскую папку `mods` соответствующего профиля. Уберите из неё предыдущий RenderScope JAR: в профиле должен быть один файл мода для его загрузчика.
3. Загрузите мир, дайте профилировщику несколько секунд и нажмите **F8** или **Esc → RenderScope**.

MixinExtras включён в основной JAR. Файлы `-slim`, `-sources` и `-compat-harness` для обычной установки не используются. **Установка на сервер не требуется.** Клавиша меняется через **Настройки → Управление → RenderScope**.

В **Mods** выберите мод, в **Objects** — тип и экземпляр. **Highlight** подсвечивает его, **Track** сохраняет отслеживание, **Copy Position** копирует позицию. **Heatmap** и **Inspect** включаются в интерфейсе.

По умолчанию работает **Timing Only**: CPU-side renderer/visual callbacks с раздельными источниками. **Full** добавляет наблюдаемые BufferBuilder vertices/RenderType и живую асинхронную GPU-диагностику общих Flywheel batches на поддерживаемом контексте. Общие CPU/GPU-фазы не распределяются между объектами; per-object instanced geometry, GPU time и VRAM не вычисляются. CPU sources и shared phases входят в JSON, живая GPU-сводка — нет.

Для отчёта запустите **Capture**, завершите запись и нажмите **Export JSON**. Файл находится в `renderscope-reports` игрового профиля. Координаты и dimension выключены по умолчанию; имена игроков, серверы, чат, UUID и абсолютные пути не экспортируются.

## Проверенный артефакт

Основной JAR: SHA-256 **`2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`**; текущая сборка проходит **36 unit/privacy-тестов**. LICENSE: SHA-256 `cf2bc5c60fc35b9f87e8c001367cd7073a52c5604daf5a93437798900d1a94b6`. Лицензия и сторонние уведомления внутри JAR и обоих дистрибутивов побайтно совпадают с исходными текстами.

Vanilla baseline от **2026-10-20T20:52:07.624400300Z** сохранён для побайтно неизменных 82 production .class; после добавления лицензии выполнены пересборка и тесты, новый vanilla-запуск не заявляется. Сторонний installed-сценарий Create/Mekanism относится к Forge, а не к этому NeoForge JAR.

## Материалы

- [LICENSE](LICENSE) — RenderScope Source-Available License 1.0; [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) — отдельные условия сторонних компонентов.
- [MODRINTH.md](MODRINTH.md), [CURSEFORGE.md](CURSEFORGE.md), [DESCRIPTION_RU.md](DESCRIPTION_RU.md) — локальные описания 0.2.0.
- `brand/` — оригинальное лого и точные целочисленные увеличения без добавленных полей.
- `gallery/` — игровые скриншоты и GIF.
- [verification.json](verification.json) — текущий SHA-256 JAR и точная область подтверждённых проверок.
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) — список проведённых и оставшихся проверок.

Этот **NeoForge 0.2.0** доступен в [публичном GitHub-репозитории](https://github.com/AlexFirst404/RenderScope); в Modrinth и CurseForge NeoForge-файл не загружен. Отдельный **Forge 0.2.0** сохранён как [Private Alpha в Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu) и не подходит для NeoForge-профиля. Историческая Private-версия Modrinth 0.1.0 сохранена. CurseForge остаётся авторским проектом **New без JAR**, с Custom-лицензией, публичной ссылкой на исходники, EN/RU-описанием и пятью новыми gallery media; отправка файла на модерацию не выполнялась.

Публичные исходники: [AlexFirst404/RenderScope](https://github.com/AlexFirst404/RenderScope). Собственная **source-available** лицензия разрешает изучение, изменения и включение в бесплатные проекты и модпаки с сохранением лицензии и уведомлений. Продажа, платный доступ и включение в платные проекты, подписки или модпаки требуют предварительного письменного разрешения правообладателя. Публиковать личные изменения не требуется. Это не MIT и не OSI-approved open source; полный английский текст [LICENSE](LICENSE) определяет условия.

## English

Use **Minecraft 1.20.1**, **NeoForge 47.1.106** and **Java 21**. Put `renderscope-neoforge-0.2.0+mc1.20.1.jar` in your client profile's `mods` folder, with one RenderScope JAR matching that loader. MixinExtras is bundled. Open a world, then press **F8** or **Esc → RenderScope**. The server does not need RenderScope.

Renderer and supported Flywheel visual CPU work is measured per instance. Full mode adds observed geometry and asynchronous shared Flywheel batch GPU diagnostics; no per-object GPU time or instanced vertex allocation is invented. JSON exports include CPU sources/shared phases, with positions off by default. See `verification.json` for the exact tested artifact and scope.

RenderScope uses the custom **RenderScope Source-Available License 1.0**. Modification and inclusion in freely available projects/modpacks are allowed subject to its notices and conditions. Selling it, charging for access, or including it in paid projects/modpacks requires prior written permission. See [LICENSE](LICENSE); third-party components retain their own licenses.

The [GitHub repository](https://github.com/AlexFirst404/RenderScope) is Public. Forge 0.2.0 is also saved as a [private Modrinth Alpha](https://modrinth.com/mod/renderscope/version/vHpqVjbu), with its downloaded JAR hash verified; NeoForge 0.2.0 is available through GitHub only. CurseForge remains a new author-only draft without a JAR. These states do not constitute a public Modrinth/CurseForge release.
