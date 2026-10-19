# RenderScope 0.1.0

## Установка

1. Minecraft Java **1.20.1**, NeoForge **47.1.106**, **Java 21**.
2. Положите `renderscope-neoforge-0.1.0+mc1.20.1.jar` в `mods` клиентского профиля.
3. Загрузите мир, подождите несколько секунд и нажмите **F8** или **Esc → RenderScope**.

Зависимый MixinExtras уже включён. На сервер устанавливать RenderScope не требуется.
Клавиша меняется через обычное меню управления Minecraft.

В **Mods** выберите мод, в **Objects** — тип и экземпляр. **Highlight** подсвечивает его,
**Track** сохраняет отслеживание. **Heatmap** и **Inspect** включаются в интерфейсе.
Режим **Full** добавляет наблюдаемые вершины и RenderType; по умолчанию работает **Timing Only**.

Для отчёта запустите **Capture**, завершите запись и нажмите **Export JSON**.
Результат находится в `renderscope-reports` игрового профиля; координаты по умолчанию исключены.

Измеряется CPU-side время EntityRenderer и BlockEntityRenderer. GPU time, VRAM,
terrain/particles/GUI и отдельные shader passes не входят в эту версию.

## Материалы

- `MODRINTH.md`, `CURSEFORGE.md`, `DESCRIPTION_RU.md` — подготовленные описания.
- `brand` — исходный пиксельный логотип, точные увеличения, иконки и обложка.
- `gallery` — настоящие игровые скриншоты и `heatmap-demo.gif` из 16 кадров.
- `verification.json` — SHA-256 JAR и результаты проверок.
- `smoke-result.json` — результаты запуска Minecraft с тестовым миром.
- `smoke-capture-example.json` — настоящий пример экспорта с выключенными координатами.

Публикация на площадках ещё не выполнялась. Перед публикацией загрузите изображения
в редактор площадки и замените относительные ссылки описания ссылками загруженных файлов.

## English

Use Minecraft **1.20.1**, NeoForge **47.1.106** and **Java 21**. Put the bundled
RenderScope JAR into your client profile's `mods` folder. Open a world, then press
**F8** or **Esc → RenderScope**. No RenderScope server installation is required.
Measurements are CPU-side renderer timings; Full mode adds observed geometry estimates.

The build passed 17 unit/privacy tests and a real development-client smoke run.
External vanilla-server connections and third-party render/shader compatibility have
not been tested in this workspace. See the release checklist for the exact validation scope.
