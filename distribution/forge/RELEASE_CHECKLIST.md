# RenderScope 0.1.0 — Forge / Minecraft 1.20.1

Материалы Forge-релиза для Modrinth и CurseForge. Проверки отмечаются по результатам отдельной Forge-сборки и Forge smoke run. Ранее полученные результаты NeoForge не подменяют проверку этого JAR. Сцена Create + Mekanism прошла и development-, и установленную production-проверку. CurseForge остаётся авторским New-черновиком без JAR; основной Forge JAR загружен в Private-проект Modrinth как Alpha 0.1.0+mc1.20.1. Публичная версия не опубликована.

## Метаданные

| Поле | Значение текущей сборки |
| --- | --- |
| Название | RenderScope |
| Mod ID | `renderscope` |
| Версия | `0.1.0+mc1.20.1` |
| Тип релиза | Alpha |
| Minecraft | `1.20.1` |
| Загрузчик | Forge, целевая версия `47.4.10` |
| Среда | Client required / Server unsupported, установка на сервер не нужна |
| Java | Профиль запуска и сборка на Java 21; bytecode release 17 |
| Краткое описание EN | Find the exact entities and block entities behind expensive renderer calls. |
| Краткое описание RU | Найдите конкретные сущности и блок-сущности с дорогими вызовами рендера. |
| Лицензия | All Rights Reserved, пока владелец не выберет другую |
| Зависимости | Forge; MixinExtras включён в основной JAR |

## Сборка и содержимое

- [x] На JDK 21 выполнена Forge-сборка; текущие XML подтверждают **22 unit/privacy-теста**, без ошибок: 16 core, 4 deferred-context, 2 privacy.
- [x] Собран **основной** `build/forge/libs/renderscope-forge-0.1.0+mc1.20.1.jar`.
- [x] Основной JAR отличается от `-slim` и `-sources` и содержит MixinExtras runtime.
- [x] Проверены `mods.toml`, refmap, MixinConfigs и вложенные библиотеки.
- [x] В release JAR нет тестового smoke-мода, сгенерированного мира, отчётов и `.tools`.
- [ ] SHA-256 опубликованного файла совпадает с проверенным локальным артефактом.

## Проверка в игре

- [x] Проверен основной production JAR в отдельном установленном профиле Forge 47.4.10 / Minecraft 1.20.1 / Java 21; helper подтвердил его SHA-256 и свежий runtime-результат.
- [x] F8 зарегистрирован через стандартный KeyMapping с категорией Controls → RenderScope.
- [ ] В установленном профиле вручную проверены F8 и переназначенная клавиша.
- [x] Кнопка Esc → RenderScope добавлена; обычный Pause Menu сохранён и снят на скриншоте.
- [x] Overview / Mods / Objects показывают реальные вызовы сущностей и блок-сущностей в запущенном Minecraft на Forge 47.4.10.
- [ ] Поиск, сортировка в обоих направлениях, scroll и переход к экземпляру работают.
- [x] В игровом smoke run работают world bounding boxes, выделение и Tracking HUD.
- [ ] В установленном профиле вручную проверены Copy Position и истечение таймера подсветки.
- [x] Heatmap реально отображает сравнительные цвета на объектах мира.
- [ ] Heatmap корректно применяет категории, дистанцию, порог, лимит и переключатели подписей/глубины.
- [x] Inspector показывает измеренные данные chest под прицелом в игровом smoke run.
- [x] Timeline показывает реальные кадры; выбранный кадр и его фактическая атрибуция проверены в игровом smoke run.
- [x] В Minecraft на Forge завершена 5-секундная запись (593 кадра) и выполнен Export JSON; runtime loader/version подтверждены как Forge 47.4.10.
- [x] Unit-тесты проверяют ручную остановку и автоматическое завершение 5-секундной записи.
- [ ] В установленном профиле проверены UI-записи на 10/30 секунд и ручной режим.
- [x] Timing Only покрыт unit-тестом и smoke fallback; Full в Minecraft даёт наблюдаемые вершины стандартного BufferBuilder.
- [x] Unit-тесты проверяют сброс сессии, записей и игнорирование поздних вызовов.
- [ ] В установленном профиле проверены перезаход и смена dimension, включая выделения.
- [x] Английский Overview на Forge визуально проверен при scaled 320×240 и в широком окне 1680×720; кнопки и scroll не перекрываются.
- [ ] В установленном профиле дополнительно проверены русский интерфейс и остальные вкладки при разных GUI Scale.
- [ ] Выполнено подключение к серверу без RenderScope; собственная серверная установка не требуется.
- [ ] Любая совместимость с конкретным сторонним render/shader/optimization mod подтверждена отдельной проверкой, прежде чем упоминать его на странице.

### Create + Mekanism: текущая проверка

- [x] Сценарий закреплён за Forge 47.4.10, Create 6.0.8, Flywheel 1.0.5, Ponder 1.0.91 и файлом Mekanism-1.20.1-10.4.16.80.jar.
- [x] Подготовлен отдельный source set `compat` и production/reobfuscated `compat-harness.jar`; он не входит в основной мод.
- [x] Добавлен optional Mekanism deferred hook с weak identity contexts, лимитом 4 096 за кадр, реальным callback timer и сохранением исключений.
- [x] Unit-тесты проверяют identity, неоднозначную ассоциацию, лимиты, очистку и weak-reference cleanup.
- [x] Настоящий development-клиент завершил `compatibility/compat-result.json` с `success: true`, Forge 47.4.10 и точными целевыми модами.
- [x] Заряженный Energy Cube задействовал queued/rendered/measured deferred counters: по 673 в Full и по 506 в Timing Only.
- [x] Full/Timing Only, заполненные баки, механизмы, отдельные boxes/labels и capture/export подтверждены реальным сценарием; 594 кадра за 5,005 секунды.
- [x] Исходный `flywheel:indirect` и fallback `flywheel:off` проверены; backend восстановлен, вал вращался на 32 rpm и дал 216 наблюдаемых вершин/кадр в fallback.
- [x] Production JAR и отдельный harness проверены обычным установленным Forge launcher без development/mapped classpath: `compatibility-installed/installed-verification.json`, `environment: installed-production`, 649 deferred-вызовов в Full и 504 в Timing Only.
- [x] Новый vanilla baseline Forge без Mekanism подтвердил необязательность deferred mixin; текущие 22 unit/privacy-теста пройдены.
- [x] В текстах явно указано: Flywheel visuals/instancing/batching и виртуальные дочерние BEs contraption не получают полной per-object атрибуции или отдельной подсветки.

Команды: `gradlew.bat -Ploader=forge test build compatJar`, затем `gradlew.bat -Ploader=forge runCompatClient`. Development-результаты сохраняются в `compatibility/`. Для установленного профиля: `python scripts/test-installed-client.py --harness build/forge/libs/renderscope-forge-0.1.0+mc1.20.1-compat-harness.jar --launch`; отдельные результаты — `compatibility-installed/`. Код завершения процесса не заменяет проверку свежего JSON-результата.

## Privacy regression

- [x] Privacy-тест: при `Include world positions = OFF` отсутствуют position и dimension, включая worst-instance timeline entries.
- [x] Privacy-тест: при `ON` добавляются разрешённые position/dimension, без сырых ключей и UUID.
- [x] Проверен явный список полей: не экспортируются UUID, внутренние entity IDs, player/server/world/chat и absolute paths.
- [x] Реальный JSON содержит версии Minecraft/Forge/RenderScope и список установленных модов с версиями.
- [x] Unit-тест проверяет, что capture охватывает всю запись, даже после выпадения первых кадров из rolling window.

## Галерея и анимация

Файлы галереи игрового smoke run:

| Файл в `gallery/` | Содержание |
| --- | --- |
| `01-overview.png` | Живая сводка с измеренными вызовами |
| `02-mods.png` | Таблица модов |
| `03-objects.png` | Типы сущностей или блок-сущностей |
| `04-object-detail.png` | Реальный тип объекта и список экземпляров |
| `05-timeline.png` | История кадров и выбор точки |
| `06-capture.png` | Завершённая реальная запись |
| `07-heatmap-controls.png` | Настройки теплокарты |
| `08-pause-menu.png` | Кнопка в обычном Pause Menu |
| `09-world-heatmap.png` | Цветные bounding boxes в мире |
| `10-crosshair-inspector.png` | Метрики объекта под прицелом |
| `11-small-gui-320x240.png` | Минимальное scaled-разрешение 320×240 |
| `12-ultrawide-1680x720.png` | Широкое окно 1680×720 |
| `heatmap-demo.gif` | Анимация из фактических игровых кадров, если создана |

- [x] Скриншоты Forge smoke run получены из запущенного Minecraft, без подмены profiler values.
- [x] Просмотренные кадры показывают локальную тестовую сцену без личных данных и серверных адресов.
- [x] GIF собран из 16 реальных игровых кадров Forge, 1280×720, без нарисованного интерфейса и искусственных цифр.
- [x] Финальные Forge heatmap/inspector, малое/широкое окно и Forge-обложка просмотрены; пиксельное лого не размыто.
- [x] В закрытый авторский черновик CurseForge загружены логотип и четыре gallery media.
- [x] В Private-проект Modrinth загружены иконка и пять gallery media: featured-обложка, игровой GIF, таблица модов, общая теплокарта и выделенный экземпляр Mekanism из installed-сцены.
- [x] В обоих редакторах сохранены английские описания с полноразмерными изображениями и фактическими CDN-ссылками. CurseForge сохраняет четыре собственных gallery media; дополнительные installed-кадры включены в описание.

## Бренд и тексты

Исходное лого: `brand/renderscope-original.png` (24×24). Увеличенные целочисленные версии: 192×192, 384×384, 768×768. Квадратные иконки с полями в цвет фона исходника: `renderscope-icon-512.png` и `renderscope-icon-1024.png`. Обложка: `brand/renderscope-cover-1600x900.png`. Масштабирование рисунка — nearest neighbor, без сглаживания.

- [x] Подготовлены `MODRINTH.md`, `CURSEFORGE.md` и русская версия `DESCRIPTION_RU.md`.
- [x] В текстах нет обещаний GPU timing, VRAM, полной атрибуции всего рендера, Fabric-сборки или непроверенной совместимости.
- [x] Локальный `release.json` указывает Minecraft 1.20.1, Forge 47.4.10, alpha, клиентскую сторону и текущую лицензию.
- [x] В Private-проект Modrinth загружена [Alpha-версия 0.1.0+mc1.20.1](https://modrinth.com/mod/renderscope/version/YzYFQvOb): основной `renderscope-forge-0.1.0+mc1.20.1.jar`, проверенный в installed-профиле, исходный SHA-256 `42b8b37b3e5f79a2695ab6c448cca47b587c65abf54d8345f26e0b1caf61a309`.
- [x] JAR скачан из интерфейса созданной версии Modrinth; его SHA-256 точно совпал с installed-артефактом: `42b8b37b3e5f79a2695ab6c448cca47b587c65abf54d8345f26e0b1caf61a309`.
- [ ] Заполнены реальные ссылки исходников/обсуждения/ошибок, если владелец решил их публиковать; вымышленные ссылки не используются.
- [ ] Владелец отдельно разрешил публичную публикацию, версия отправлена на review и опубликована.

## Статус редакторов площадок

- **CurseForge:** ещё не одобренный авторский проект **New**, ID **1756581**; [редактор файлов](https://authors.curseforge.com/#/projects/1756581/files). Логотип и четыре медиафайла загружены. **JAR не загружен; отправка на модерацию и публикация не выполнены.** Первая загрузка JAR запускает модерацию проекта, а unlisted доступен по прямой ссылке и не является закрытым релизом. До согласованного безопасного сценария файл остаётся локальным.
- **Modrinth:** [проект RenderScope](https://modrinth.com/project/renderscope/settings) имеет проверенную видимость **Private**; checklist подтверждает доступ только участникам. Сохранены иконка, английское описание с полноразмерными изображениями и пять gallery media. Основной Forge JAR загружен как [Alpha 0.1.0+mc1.20.1](https://modrinth.com/mod/renderscope/version/YzYFQvOb) для Minecraft 1.20.1, только клиент; скачанный файл побайтно соответствует проверенному артефакту по SHA-256. **Send for review не нажат, публичная версия не опубликована.**
- Актуальный общий снимок кабинетов хранится в `distribution/platforms/publication-status.json`; локальное наличие ZIP/JAR не означает публикацию на площадке.
