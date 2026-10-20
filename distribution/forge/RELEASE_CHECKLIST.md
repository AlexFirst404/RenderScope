# RenderScope 0.2.0 — Forge / Minecraft 1.20.1

Версия **Forge 0.2.0** доступна в публичном [GitHub-репозитории](https://github.com/AlexFirst404/RenderScope) и загружена как Alpha в [Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu) и [CurseForge](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271697). Отдельная NeoForge 0.2.0 также загружена на обе площадки. Modrinth-проект и оба CF-файла Under Review; скачанные JAR совпали с соответствующими локальными артефактами. Публичность после одобрения разрешена; одобрение пока не подтверждено.

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
| Лицензия | [RenderScope Source-Available License 1.0](LICENSE), `LicenseRef-RenderScope-Source-Available-1.0` |

## Текущая сборка и baseline

- [x] Финальная текущая сборка и unit/privacy-тесты подтверждены `verification.json` для точного 0.2.0 JAR.
- [x] Проверены metadata, refmap, bundled MixinExtras, release17 и отсутствие smoke/compat-кода в основном JAR.
- [x] Реальный baseline Forge 47.4.10 без Create/Mekanism подтвердил необязательность интеграций.
- [x] В текущем baseline проверены F8/Controls, кнопка Pause Menu, настоящие entity/BE измерения и оба режима.
- [x] В текущем baseline проверены highlights/tracking/heatmap/inspector и отзывчивость 320×240/широкого окна.
- [x] В текущем baseline завершён реальный 5-секундный capture, проверены loader/version и export privacy.

Число тестов, кадры, время завершения и SHA-256 берутся только из финального `verification.json` и текущих smoke-файлов; показатели старой 0.1.0 не считаются результатами 0.2.0.

Финальная Forge-сборка после добавления лицензии: **36 тестов**, 0 failures/errors; SHA-256 текущего JAR **`1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`**. Vanilla baseline завершён **2026-10-20T20:50:33.028882800Z** на предыдущем JAR `687e6a56328baaa981da80d5e5413020bfcca03e37ece5f81e08f4c74198457a`. Все **82 production .class** в новом JAR побайтно совпадают с тем baseline-артефактом: изменились юридические metadata/тексты, повторный vanilla-запуск не заявляется. Сохранены его **28** игровых PNG.

## Flywheel и выбранная сцена

- [x] Финальный установленный production JAR прошёл `compatibility-installed/installed-verification.json` с совпадающим SHA-256.
- [x] Настоящие Create 6.0.8 / Flywheel 1.0.5 / Ponder 1.0.91 и Mekanism 10.4.16.80 подтверждены runtime metadata и vendor-JAR hashes.
- [x] При активном `flywheel:indirect` положительно измерены motor UPDATE, shaft/cogwheel TICK и press FRAME в Full и Timing Only.
- [x] Shared `flywheel:batch_submit` имеет реальные CPU sources; Full имеет завершённые асинхронные GPU queries, Timing Only их выключает.
- [x] Instanced per-object vertices/bytes не выдумываются; shared GPU time не распределяется между экземплярами.
- [x] Отдельные shaft/motor/press highlights, четыре механизма в heatmap, удаление/recreate visual и настоящий disconnect прошли проверку.
- [x] Mekanism deferred queue/rendered/measured callbacks положительны в обоих режимах; реальный capture содержит CPU sources/phases и исключает приватные поля.
- [x] Сохранены все **11** настоящих installed-скриншотов; query/selection/lifecycle результаты связаны с текущим JAR.

Финальный installed-результат юридически обновлённого JAR завершён **2026-10-20T21:17:02.977824300Z**, SHA-256 **`1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`**. Capture: **593 кадров / 5.0061445 с**. Настоящие deferred callbacks: **657 Full / 503 Timing Only**; visual callbacks: **728 frame / 495 tick / 32 update**. Shared GPU Full: **591 завершённых измеренных кадров**, **731 batches**, без dropped batches; Timing Only queries выключены. Все 11 installed-скриншотов и проверка launcher связаны с этим JAR. Это показатели тестовой сцены, а не универсальная стоимость Create.

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

## Лицензия и исходники

- [x] Бесплатные модификации и включение в бесплатные проекты/модпаки разрешены собственной source-available лицензией; продажа, платный доступ и платные содержащие проекты/модпаки требуют предварительного письменного разрешения.
- [x] [LICENSE](LICENSE) и [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) соответствуют корневым текстам; MixinExtras сохраняет свою MIT-лицензию.
- [x] Release-скрипты требуют точный LicenseRef в metadata и побайтное совпадение лицензии/уведомлений в основном JAR и ZIP.
- [x] После смены лицензии подтверждены текущий JAR и точные вложенные юридические тексты; новый Forge installed-run и сохранённые baseline-результаты описаны с их фактической областью.
- [x] Финальный ZIP после добавления NeoForge-версии Modrinth проверен повторно: основной JAR и LICENSE/notices совпадают побайтно, исторические/test JAR и raw presentation PNG исключены.
- [x] Для [GitHub-репозитория](https://github.com/AlexFirst404/RenderScope) проверена видимость Public через API и анонимный браузер; актуальные исходники и материалы отправлены исходным коммитом `1f3e988`.

SHA-256 текста LICENSE: `cf2bc5c60fc35b9f87e8c001367cd7073a52c5604daf5a93437798900d1a94b6`. Полные legal-хеши и побайтные проверки сохранены в [verification.json](verification.json).

Условия RenderScope не обозначаются как MIT или OSI-approved open source. Исторический JAR 0.1.0 не перепаковывался; прежняя Private-видимость сохранена как датированный снимок, а не обещание будущей приватности проекта.

## Состояние площадок и история

- [x] Историческая Modrinth [Alpha 0.1.0](https://modrinth.com/mod/renderscope/version/YzYFQvOb) и её прежний JAR/hash не заменены версией 0.2.0; прежние Private-снимки сохранены в metadata history.
- [x] Иконки в Modrinth/CurseForge исправлены с полным исходным рисунком; доказательства находятся в `distribution/platforms/` репозитория.
- [x] Forge [Alpha 0.2.0 в Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu) и [Alpha 0.2.0 в CurseForge](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271697) загружены; оба повторно скачанных JAR — 371 686 байт, SHA-256 `1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`, точно совпадают с installed-артефактом.
- [x] Исходная заявка Modrinth от **2026-10-21 00:37 Europe/Moscow** подтверждена сообщением «Your project has been submitted for review!». Свежая страница показывает проект **«На рассмотрении»** и сохранение очереди после изменений; NeoForge добавлен в ту же заявку, повторного submission не было. Proofs `modrinth-moderation-020.png`, `modrinth-neoforge-moderation-020.png`.
- [x] [NeoForge Alpha 0.2.0 в Modrinth, версия 41g9JyXE](https://modrinth.com/mod/renderscope/version/41g9JyXE) добавлена в существующий проект на рассмотрении; повторно скачанный JAR — 371 691 байт, SHA-256 `2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`, совпадает с локальным NeoForge-артефактом. Автодетект Forge исправлен и повторно прочитан как только NeoForge / Minecraft 1.20.1 / Alpha / client-only; proofs `modrinth-neoforge-020-verification.json`, `modrinth-neoforge-version-020.png`.
- [x] CurseForge проект 1756581, файл 9271697 имеет статус **Under Review**; сохранены Forge/client/Java21/Minecraft1.20.1/Alpha, EN/RU changelog и автоматическая публикация после approval. Proof `curseforge-moderation-020.png`, remote hash — `curseforge-020-verification.json`.
- [x] Отдельная [NeoForge Alpha 0.2.0 в CurseForge, файл 9271865](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271865) имеет **Under Review**. Повторно скачанный JAR — 371 691 байт, SHA-256 `2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`, совпадает с локальным NeoForge-артефактом; proof `curseforge-neoforge-020-verification.json`.
- [x] У CF-файлов Forge 9271697 и NeoForge 9271865 повторно прочитаны **Client** на уровне файла, только соответствующий loader, Java 21, Minecraft 1.20.1, Alpha и автоматическая публикация после approval. Proofs: `curseforge-forge-client-020.png`, `curseforge-neoforge-client-020.png`, `curseforge-loaders-moderation-020.png`; изменение отдельной project-level environment-настройки не заявляется.
- [x] Новое разрешение пользователя на публичность и отправку обоих проектов выполнено; прежнее удержание CF JAR по условию приватности перенесено в историю.
- [x] Modrinth EN/RU-описание сохранено для двух loader: подтверждение «Описание обновлено», три изображения загрузились; proof `modrinth-description-loaders-020.png`.
- [ ] Модераторы одобрили проекты и подтверждена состоявшаяся публичная публикация. На текущий момент approval не заявляется.

Авторитетный снимок кабинетов: `distribution/platforms/publication-status.json`. Публичный GitHub и заявки на модерацию Modrinth/CurseForge — разные состояния. Действующие статусы Under Review и предыдущие приватные snapshots записаны отдельно; загрузка и submission не означают approval.
