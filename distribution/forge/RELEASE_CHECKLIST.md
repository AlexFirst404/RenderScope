# RenderScope 0.2.0 — Forge / Minecraft 1.20.1

Локальный релиз **0.2.0**. Историческая Private Alpha **0.1.0** остаётся в Modrinth; 0.2.0 туда не загружалась. CurseForge — неодобренный авторский черновик без JAR. Старые JAR сохранены, но не включаются в пакет новой версии.

## Цель и артефакт

| Поле | Значение |
| --- | --- |
| Mod ID | `renderscope` |
| Версия | `0.2.0+mc1.20.1`, Alpha |
| Minecraft / loader | 1.20.1 / Forge 47.4.10 |
| Java | JDK/runtime 21, bytecode release 17 |
| Дистрибутив | `renderscope-forge-0.2.0+mc1.20.1.jar` |
| Сторона | Client required, server unsupported; серверная установка не нужна |
| Зависимости | Forge; MixinExtras включён в JAR |
| Лицензия | All Rights Reserved |

## Текущая сборка и baseline

- [x] Финальная текущая сборка и unit/privacy-тесты подтверждены `verification.json` для точного 0.2.0 JAR.
- [x] Проверены metadata, refmap, bundled MixinExtras, release17 и отсутствие smoke/compat-кода в основном JAR.
- [x] Реальный baseline Forge 47.4.10 без Create/Mekanism подтвердил необязательность интеграций.
- [x] В текущем baseline проверены F8/Controls, кнопка Pause Menu, настоящие entity/BE измерения и оба режима.
- [x] В текущем baseline проверены highlights/tracking/heatmap/inspector и отзывчивость 320×240/широкого окна.
- [x] В текущем baseline завершён реальный 5-секундный capture, проверены loader/version и export privacy.

Число тестов, кадры, время завершения и SHA-256 берутся только из финального `verification.json` и текущих smoke-файлов; показатели старой 0.1.0 не считаются результатами 0.2.0.

Финальная Forge-сборка: **36 тестов**, 0 failures/errors. Vanilla baseline завершён **2026-10-20T20:50:33.028882800Z**, capture **593 кадров / 5.0037815 с**, **28** игровых PNG. SHA-256 текущего JAR: `687e6a56328baaa981da80d5e5413020bfcca03e37ece5f81e08f4c74198457a`.

## Flywheel и выбранная сцена

- [x] Финальный установленный production JAR прошёл `compatibility-installed/installed-verification.json` с совпадающим SHA-256.
- [x] Настоящие Create 6.0.8 / Flywheel 1.0.5 / Ponder 1.0.91 и Mekanism 10.4.16.80 подтверждены runtime metadata и vendor-JAR hashes.
- [x] При активном `flywheel:indirect` положительно измерены motor UPDATE, shaft/cogwheel TICK и press FRAME в Full и Timing Only.
- [x] Shared `flywheel:batch_submit` имеет реальные CPU sources; Full имеет завершённые асинхронные GPU queries, Timing Only их выключает.
- [x] Instanced per-object vertices/bytes не выдумываются; shared GPU time не распределяется между экземплярами.
- [x] Отдельные shaft/motor/press highlights, четыре механизма в heatmap, удаление/recreate visual и настоящий disconnect прошли проверку.
- [x] Mekanism deferred queue/rendered/measured callbacks положительны в обоих режимах; реальный capture содержит CPU sources/phases и исключает приватные поля.
- [x] Сохранены все **11** настоящих installed-скриншотов; query/selection/lifecycle результаты связаны с текущим JAR.

Финальный installed-результат завершён **2026-10-20T20:49:18.002353600Z**, SHA-256 **`687e6a56328baaa981da80d5e5413020bfcca03e37ece5f81e08f4c74198457a`**. Capture: **592 кадров / 5.0057879 с**. Настоящие deferred callbacks: **653 Full / 503 Timing Only**; visual callbacks: **734 frame / 499 tick / 31 update**. Shared GPU Full: **593 завершённых измеренных кадров**, **729 batches**, без dropped batches; Timing Only queries выключены. Это показатели тестовой сцены, а не универсальная стоимость Create.

Development-результат `compatibility/compat-result.json` и его семь изображений сохранены как **история 0.1.0**. Новый development compat run для 0.2.0 не заявляется; текущая проверка сторонней сцены выполняется установленным production-клиентом.

## Пиксельное лого и материалы

- [x] Исходник 24×24 сохранён побайтно; исходные чёрные пиксели не удалялись.
- [x] Все RGBA-пиксели увеличений 192/384/768/1536 проверены относительно целочисленного nearest-neighbor.
- [x] Иконки `brand/renderscope-icon-768.png` и `brand/renderscope-icon-1536.png` занимают весь исходный квадрат, без добавленных полей/рамок.
- [x] Старые padded 512/1024 ассеты удалены из текущих brand-каталогов; `pixel-integrity.json` имеет schema2 и нулевые padding values.
- [x] Подготовлены локальные EN/RU-описания 0.2.0 с ясным разделением per-instance CPU и shared GPU.
- [x] В текстах GPU-диагностика описана как живая сводка завершённых измеренных Flywheel-кадров; CPU sources/phases экспортируются отдельно.
- [x] Финальные gallery/cover/GIF соответствуют текущему baseline; все локальные ссылки и ZIP проверены.

## Оставшиеся самостоятельные проверки

- [ ] Подключение к внешнему серверу без RenderScope.
- [ ] Ручное переназначение F8, поиск/двунаправленная сортировка/scroll, Copy Position и timeout выделения.
- [ ] UI-записи 10/30 секунд и ручной режим в установленном профиле.
- [ ] Все heatmap-фильтры, русский интерфейс и остальные GUI scales вручную.
- [ ] Дополнительные shader/renderer версии подтверждены отдельной проверкой.

## История площадок

- [x] Историческая Modrinth [Alpha 0.1.0](https://modrinth.com/mod/renderscope/version/YzYFQvOb) сохранена Private; её прежний JAR/hash не заменён версией 0.2.0.
- [x] Иконки в Modrinth/CurseForge исправлены с полным исходным рисунком; доказательства находятся в `distribution/platforms/` репозитория.
- [x] CurseForge проект 1756581 остаётся неодобренным авторским черновиком без JAR, без отправки файла на модерацию.
- [ ] Отдельно разрешены и выполнены публикация/загрузка новой 0.2.0 версии на площадки.

Авторитетный снимок кабинетов: `distribution/platforms/publication-status.json`. Наличие локального ZIP/JAR и успешных тестов не означает загрузку новой версии на платформу.
