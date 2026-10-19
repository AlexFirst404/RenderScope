# RenderScope 0.1.0 — Forge / Minecraft 1.20.1

## Установка

1. Используйте **Minecraft Java 1.20.1**, **Forge 47.4.10** и **Java 21**.
2. Положите [renderscope-forge-0.1.0+mc1.20.1.jar](renderscope-forge-0.1.0+mc1.20.1.jar) в папку `mods` клиентского профиля Forge.
3. Загрузите мир, подождите несколько секунд и нажмите **F8** или **Esc → RenderScope**.

MixinExtras уже включён в основной JAR. На сервер устанавливать RenderScope не требуется. Клавиша меняется через **Настройки → Управление → RenderScope**.

В одном профиле должен быть только один RenderScope JAR для соответствующего загрузчика. NeoForge-сборка сохранена отдельно; её JAR не нужно добавлять в этот Forge-профиль. Файлы `-slim` и `-sources` для установки не используются.

В **Mods** выберите мод, в **Objects** — тип и экземпляр. **Highlight** подсвечивает его, **Track** сохраняет отслеживание, **Copy Position** копирует позицию. **Heatmap** и **Inspect** включаются в интерфейсе. По умолчанию работает **Timing Only**; режим **Full** добавляет наблюдаемые вершины и RenderType.

Для отчёта запустите **Capture**, завершите запись и нажмите **Export JSON**. Результат находится в `renderscope-reports` игрового профиля. Координаты и dimension по умолчанию исключены; включить их можно только в настройках отчётов.

Измеряется CPU-side время мировых EntityRenderer и BlockEntityRenderer. GPU time, VRAM, terrain/particles/GUI и отдельные shader passes не входят в эту версию.

## Материалы

- [MODRINTH.md](MODRINTH.md), [CURSEFORGE.md](CURSEFORGE.md), [DESCRIPTION_RU.md](DESCRIPTION_RU.md) — подготовленные описания именно Forge-сборки.
- `brand/` — исходный пиксельный логотип, точные увеличения, иконки и Forge-обложка.
- `gallery/` — игровые скриншоты и GIF из настоящего запуска Minecraft на Forge.
- [verification.json](verification.json) — SHA-256 JAR и результаты проверок.
- [smoke-result.json](smoke-result.json) — результаты отдельного запуска Forge 47.4.10 с тестовым миром.
- [smoke-capture-example.json](smoke-capture-example.json) — реальный пример экспорта с выключенными координатами.
- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) — точная область проведённых проверок.

Публикация на Modrinth/CurseForge ещё не выполнялась. Перед загрузкой описания загрузите изображения в редактор площадки и замените относительные ссылки ссылками этих материалов.

## English

Use **Minecraft 1.20.1**, **Forge 47.4.10** and **Java 21**. Put `renderscope-forge-0.1.0+mc1.20.1.jar` into the Forge client profile's `mods` folder. Open a world, then press **F8** or **Esc → RenderScope**. MixinExtras is bundled; no RenderScope server installation is required. Install only one RenderScope JAR matching the profile's loader.

The Forge build passed 17 unit/privacy tests and an actual development-client smoke run. Measurements are CPU-side renderer timings; Full mode adds observed geometry estimates. External vanilla-server connections and a third-party render/shader compatibility matrix have not been tested in this workspace.
