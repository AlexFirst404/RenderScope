# RenderScope 0.3.0

![RenderScope pixel-art logo](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/brand/renderscope-logo-384x384.png)

## English

**Find a measured render cost. Follow it to one object in your world.**

RenderScope connects object-render CPU work to a mod, a type and a specific entity or block entity. It provides **67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3**, with Fabric, Forge and NeoForge adapters. The server does not need the mod.

### Install

1. Choose **one JAR matching your exact Minecraft version and loader**. Do not install builds for different loaders together. Forge has no upstream release for 1.20.5 or 1.21.2.
2. Use **Java 25 for Minecraft 26.x**, or **Java 21 for earlier RenderScope targets**.
3. For Fabric, also install the matching **Fabric API**. Use the [exact loader and API table](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/platforms/0.3.0/DEPENDENCIES.md); pinned upstream beta versions remain identified.
4. Put the JAR in your client profile's `mods` folder. Enter a world and press **F8**, or open **Esc → RenderScope**. F8 can be rebound in Controls.

### Find the object

1. Read the first page's largest **measured object CPU** contributor. Early results are marked preliminary.
2. Open **Mods → Objects**, select a type, then inspect an instance. Registry ownership and renderer implementation ownership stay visible separately.
3. Use **Highlight** to see its box, **Track** for direction and distance, **Heatmap** to compare nearby measured objects, or **Inspect** for the object under your crosshair.

The 1.20.1–1.21.8 UI also offers **Find object / Show in world**. The 1.21.9–26.3 UI offers **Show the instance / Inspect measured mods**. English and Russian are included.

### Metrics and recording

- Read FPS/frame-time percentiles, measured CPU averages/P95/peaks, calls and the live timeline.
- Record **5 / 10 / 30 seconds** or stop manually, then export JSON to the profile's `renderscope-reports` folder.
- **Timing Only** records supported CPU intervals. **Full** adds only extended metrics the adapter can really observe.

CPU time is **not GPU time or a promised FPS improvement from removing a mod**. Parallel visual CPU intervals may overlap. Terrain, particles, GUI, shader execution and unobserved shared/deferred work are outside per-object totals.

For **1.21.9–26.3**, object metrics measure render-state extraction/submission CPU; per-object geometry and GPU timings are unavailable. A zero geometry field does not prove that the object emits no geometry.

**1.20.1** retains version-gated Flywheel visual CPU and Mekanism deferred integrations. Supported shared Flywheel GPU timestamps are asynchronous, separate from objects and live-only; capture exports CPU data. Check [exact optional-mod and shader evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/OPTIONAL-RENDERER-COMPATIBILITY.md); one tested combination does not establish support for every version.

### Privacy

Reports stay local; nothing is automatically uploaded. **Positions and dimensions are off by default.** Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded. Installed mod names/versions and measured CPU data are included.

[Build and world evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/compatibility/SUPPORT-MATRIX.md) records exact JAR hashes and distinguishes installed production clients from development clients. A build pass alone is not a gameplay or shader compatibility pass.

### Source and license

**RenderScope Source-Available License 1.0** allows personal/private tool use, modification and free redistribution/inclusion under its terms. Selling the mod/code, including it in paid projects/modpacks/bundles, or charging for access requires prior written permission. [Source and full license](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).

---

## Русский

**Найдите измеренную стоимость рендера — и конкретный объект в мире.**

RenderScope связывает CPU-работу отрисовки с модом, типом и отдельной сущностью или блок-сущностью. Подготовлены **67 отдельных клиентских сборок для 23 стабильных версий Minecraft, от 1.20.1 до 26.3**: Fabric, Forge и NeoForge. На сервер мод не нужен.

### Установка

1. Выберите **один JAR строго под свою версию Minecraft и загрузчик**. Не устанавливайте сборки для разных загрузчиков вместе. У Forge нет upstream-релизов для 1.20.5 и 1.21.2.
2. Для **Minecraft 26.x нужна Java 25**, для предыдущих целей RenderScope — **Java 21**.
3. Для Fabric дополнительно нужен подходящий **Fabric API**. Версии указаны в [точной таблице загрузчиков и API](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/platforms/0.3.0/DEPENDENCIES.md); beta-статус upstream-сборок сохранён.
4. Положите JAR в клиентскую папку `mods`. Зайдите в мир и нажмите **F8** или **Esc → RenderScope**. Клавишу можно сменить в настройках управления.

### Как найти объект

1. На первой странице посмотрите лидера **измеренной CPU-работы объектов**. Ранние результаты помечены как предварительные.
2. Откройте **Mods → Objects**, выберите тип и затем экземпляр. Владелец registry-объекта и владелец реализации renderer показаны отдельно.
3. **Highlight** подсветит объект, **Track** покажет направление и расстояние, **Heatmap** сравнит ближайшие измеренные объекты, а **Inspect** — объект под прицелом.

В UI 1.20.1–1.21.8 есть **Найти объект / Показать в мире**. В UI 1.21.9–26.3 — **Показать объект / Изучить моды**. Интерфейс доступен на русском и английском.

### Метрики и запись

- Смотрите FPS/процентили времени кадра, среднее/P95/пик измеренной CPU-работы, вызовы и живой timeline.
- Запишите **5 / 10 / 30 секунд** или остановите запись вручную, затем экспортируйте JSON в `renderscope-reports` внутри профиля.
- **Timing Only** измеряет поддерживаемые CPU-интервалы. **Full** добавляет только реально наблюдаемые адаптером расширенные метрики.

CPU-время **не является GPU-временем или обещанием прироста FPS после удаления мода**. Параллельные visual CPU-интервалы могут пересекаться. Terrain, particles, GUI, выполнение шейдеров и ненаблюдаемая общая/deferred-работа не входят в суммы отдельных объектов.

В **1.21.9–26.3** измеряется CPU extraction/submission render state; геометрия и GPU-тайминги отдельных объектов недоступны. Нулевое поле геометрии не доказывает, что объект не создаёт геометрию.

В **1.20.1** сохранены версионные интеграции visual CPU-задач Flywheel и отложенных вызовов Mekanism. Поддерживаемые общие GPU-метки Flywheel асинхронны, отделены от объектов и доступны только в live UI; capture экспортирует CPU-данные. Смотрите [точные сочетания модов и шейдеров](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/OPTIONAL-RENDERER-COMPATIBILITY.md): один успешный тест не означает поддержку всех версий.

### Приватность

Отчёты остаются локально, автоматической отправки нет. **Координаты и dimension по умолчанию выключены.** Имена игроков, адреса серверов, имена миров, чат, UUID и абсолютные пути исключены. В отчёт входят названия/версии установленных модов и измеренные CPU-данные.

[Матрица сборок и миров](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/compatibility/SUPPORT-MATRIX.md) связывает доказательства с точным хешем JAR и различает установленный клиент и dev-среду. Успешная сборка не считается игровым или shader-тестом.

### Исходники и лицензия

**RenderScope Source-Available License 1.0** разрешает личное/приватное использование как инструмента, изменения и бесплатное включение/распространение по её условиям. Продажа, включение кода/мода в платные проекты/модпаки/пакеты и платный доступ требуют предварительного письменного разрешения. [Исходники и полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).

---

## Real footage / Настоящая запись · archive 0.2.0

**Archive: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** These actual installed-client recordings show Create 6.0.8 / Flywheel 1.0.5 with the indirect backend and Mekanism 10.4.16.80. They illustrate the workflow, not current 0.3.0 validation or compatibility on other targets. The GIFs use 16 real captured frames each; rounded corners do not alter the displayed measurements.

**Архив: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** Запись настоящего установленного клиента: Create 6.0.8 / Flywheel 1.0.5 с indirect backend и Mekanism 10.4.16.80. Это иллюстрация работы, а не текущая проверка 0.3.0 или совместимости других версий. В каждом GIF 16 реально снятых кадров; скруглены только внешние углы, показанные измерения не изменены.

![Selected rotating Create shaft · archived 0.2.0 / Выбранный вращающийся вал Create · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-shaft-live.gif)

*Selected rotating Create shaft · archived 0.2.0 / Выбранный вращающийся вал Create · архив 0.2.0.*

![Selected moving Create press · archived 0.2.0 / Выбранный работающий пресс Create · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-press-live.gif)

*Selected moving Create press · archived 0.2.0 / Выбранный работающий пресс Create · архив 0.2.0.*

![Relative measured object CPU heatmap · archived 0.2.0 / Теплокарта относительной измеренной CPU-стоимости · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/heatmap.png)

*Relative measured object CPU heatmap · archived 0.2.0 / Теплокарта относительной измеренной CPU-стоимости · архив 0.2.0.*

[Recorded provenance / Происхождение записи](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/presentation/presentation-manifest.json).

## Current 0.3.0 evidence / Доказательства текущей 0.3.0

- [Measured mod table / Измеренная таблица модов](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/01-create-mekanism-mods.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [World heatmap / Теплокарта в мире](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/06-create-mekanism-heatmap.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [A selected real instance / Выбранный экземпляр мира](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/04-create-selected-instance.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
