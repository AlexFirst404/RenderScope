# RenderScope 0.3.0

![RenderScope pixel-art logo](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/brand/renderscope-logo-384x384.png)

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
3. **Highlight** подсветит объект, **Track** сохранит его отметку, **Heatmap** сравнит ближайшие измеренные объекты, а **Inspect** — объект под прицелом.

В **1.20.1–1.21.8** режим **Track** также показывает направление и расстояние, а UI предлагает **Найти объект / Показать в мире**. В **1.21.9–26.3** режим **Track** сохраняет отметку выбранного объекта в мире; UI предлагает **Показать объект / Изучить моды**. Интерфейс доступен на русском и английском.

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

## Настоящая запись · архив

**Архив: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** Запись настоящего установленного клиента: Create 6.0.8 / Flywheel 1.0.5 с indirect backend и Mekanism 10.4.16.80. Это иллюстрация работы, а не текущая проверка 0.3.0 или совместимости других версий. В каждом GIF 16 реально снятых кадров; скруглены только внешние углы, показанные измерения не изменены.

![Выбранный вращающийся вал Create · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-shaft-live.gif)

*Выбранный вращающийся вал Create · архив 0.2.0.*

![Выбранный работающий пресс Create · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-press-live.gif)

*Выбранный работающий пресс Create · архив 0.2.0.*

![Теплокарта относительной измеренной CPU-стоимости · архив 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/heatmap.png)

*Теплокарта относительной измеренной CPU-стоимости · архив 0.2.0.*

[Происхождение записи](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/presentation/presentation-manifest.json).

## Доказательства текущей 0.3.0

- [Измеренная таблица модов](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/01-create-mekanism-mods.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [Теплокарта в мире](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/06-create-mekanism-heatmap.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [Выбранный экземпляр мира](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/04-create-selected-instance.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
