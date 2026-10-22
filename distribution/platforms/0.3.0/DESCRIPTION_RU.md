# RenderScope 0.3.0

**Найдите конкретный объект с дорогой измеренной CPU-работой отрисовки.**

Клиентский профилировщик для **23 стабильных версий Minecraft Java от 1.20.1 до 26.3**: подготовлены **67 отдельных целей Fabric / Forge / NeoForge**. Выбирайте один JAR строго под свою версию игры и загрузчик. У Forge нет upstream-релизов для 1.20.5 и 1.21.2. На сервер мод не нужен.

Откройте **F8** или **Esc → RenderScope**. Первая страница показывает лидера измеренной CPU-работы объектов, отмечает предварительные данные и помогает найти самый дорогой экземпляр. Доступны таблицы модов/типов/экземпляров, Highlight/Track, теплокарта и инспектор под прицелом. Capture записывает 5/10/30 секунд либо до ручной остановки, затем сохраняет JSON локально.

**Для Minecraft 26.x нужна Java 25, для предыдущих целей RenderScope — Java 21. Fabric требует подходящий Fabric API.** [Точные загрузчики и Fabric API](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/platforms/0.3.0/DEPENDENCIES.md) указаны отдельно для каждой версии; beta-статус upstream-сборок не скрывается. Не устанавливайте JAR разных загрузчиков вместе.

В интерфейсе 1.20.1–1.21.8 используются **Главная**, **Найти объект**, **Показать в мире**, **Записать 10 с**. В 1.21.9–26.3 — **Обзор**, **Показать объект**, **Изучить моды** и вкладка записи. Есть русский и английский языки.

**CPU-измерение не является GPU-временем или обещанием прироста FPS после удаления мода.** Общая работа и параллельные visual-задачи могут пересекаться. Terrain, particles, GUI, выполнение шейдеров и ненаблюдаемая deferred/instanced-геометрия не входят в сумму отдельных объектов. Адаптеры 1.21.9–26.3 измеряют CPU extraction/submission; геометрия и GPU-тайминги явно недоступны. Full добавляет только реально доступные выбранному адаптеру метрики.

Для 1.20.1 сохранены версионные интеграции visual CPU-задач Flywheel и отложенных вызовов Mekanism. Общие асинхронные GPU-метки Flywheel отделены от механизмов. Совместимость с Sodium/Iris/Embeddium/Rubidium/Oculus/Create/Mekanism подтверждается только для [точных сочетаний](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/OPTIONAL-RENDERER-COMPATIBILITY.md), а не автоматически для всех версий игры.

Отчёты остаются в `renderscope-reports` внутри профиля. Координаты и dimension выключены по умолчанию; имена игроков, адреса серверов, имена миров, чат, UUID и абсолютные пути исключены. Автоматической отправки нет.

[Матрица проверок](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/compatibility/SUPPORT-MATRIX.md) различает установленный production-клиент и среду разработки и связывает доказательства с точным хешем JAR. Успешная сборка не считается игровым или shader-тестом.

Собственная **RenderScope Source-Available License 1.0** разрешает личное/приватное использование как инструмента, изменения и бесплатное включение/распространение по её условиям. Продажа, включение в платные проекты/модпаки/пакеты и платный доступ требуют предварительного письменного разрешения. [Исходники и полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).

![Measured mod table / Измеренная таблица модов](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/01-create-mekanism-mods.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).


![World heatmap / Теплокарта в мире](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/06-create-mekanism-heatmap.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).


![A selected real instance / Выбранный экземпляр мира](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/04-create-selected-instance.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
