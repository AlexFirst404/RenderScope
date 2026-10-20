# RenderScope 0.2.0 — NeoForge / Minecraft 1.20.1

Версия **NeoForge 0.2.0** доступна в публичном [GitHub-репозитории](https://github.com/AlexFirst404/RenderScope) и загружена как [Alpha в CurseForge, файл 9271865](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271865), статус **Under Review**. NeoForge в Modrinth не загружен. Отдельный Forge 0.2.0 находится на модерации в Modrinth и CurseForge; approval ещё не подтверждено. Forge installed-сцена не считается проверкой NeoForge.

## Цель и артефакт

| Поле | Значение |
| --- | --- |
| Mod ID | `renderscope` |
| Версия | `0.2.0+mc1.20.1`, Alpha |
| Minecraft / loader | 1.20.1 / NeoForge 47.1.106 |
| Java | JDK/runtime 21, bytecode release 17 |
| Дистрибутив | `renderscope-neoforge-0.2.0+mc1.20.1.jar` |
| Сторона | Client required, server unsupported; серверная установка не нужна |
| Зависимости | NeoForge; MixinExtras включён в JAR |
| Лицензия | [RenderScope Source-Available License 1.0](LICENSE), `LicenseRef-RenderScope-Source-Available-1.0` |

## Текущая сборка и baseline

- [x] Финальная текущая сборка и unit/privacy-тесты подтверждены `verification.json` для точного 0.2.0 JAR.
- [x] Проверены metadata, refmap, bundled MixinExtras, release17 и отсутствие smoke/compat-кода в основном JAR.
- [x] Реальный baseline NeoForge 47.1.106 без Create/Mekanism подтвердил необязательность интеграций.
- [x] В текущем baseline проверены F8/Controls, кнопка Pause Menu, настоящие entity/BE измерения и оба режима.
- [x] В текущем baseline проверены highlights/tracking/heatmap/inspector и отзывчивость 320×240/широкого окна.
- [x] В текущем baseline завершён реальный 5-секундный capture, проверены loader/version и export privacy.

Число тестов, кадры, время завершения и SHA-256 берутся только из финального `verification.json` и текущих smoke-файлов; показатели старой 0.1.0 не считаются результатами 0.2.0.

Финальная NeoForge-сборка после добавления лицензии: **36 тестов**, 0 failures/errors; SHA-256 текущего JAR **`2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`**. Vanilla baseline завершён **2026-10-20T20:52:07.624400300Z** на предыдущем JAR `ca79f3b94d5749c8b491a3081298a6dfdd86214d6d248b726667c2e921d5800b`. Все **82 production .class** в новом JAR побайтно совпадают с тем baseline-артефактом: изменились юридические metadata/тексты, повторный vanilla-запуск не заявляется. Сохранены его **28** игровых PNG.

## Flywheel и выбранная сцена

- [ ] В отдельном NeoForge-профиле проверена сторонняя Create/Mekanism сцена; Forge installed-проверка не считается этой проверкой.

Исходный Forge installed-сценарий проверяет per-visual CPU, отдельные shared CPU/GPU phases и lifecycle. Его результат относится к Forge 47.4.10 и закреплённым Create/Mekanism версиям. NeoForge baseline подтверждает базовый мод и необязательность hooks, а не полную стороннюю матрицу.

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
- [x] Финальный ZIP после добавления отдельного NeoForge CF-файла проверен повторно: основной JAR и LICENSE/notices совпадают побайтно, исторические/test JAR и raw presentation PNG исключены.
- [x] Для [GitHub-репозитория](https://github.com/AlexFirst404/RenderScope) проверена видимость Public через API и анонимный браузер; актуальные исходники и материалы отправлены исходным коммитом `1f3e988`.

SHA-256 текста LICENSE: `cf2bc5c60fc35b9f87e8c001367cd7073a52c5604daf5a93437798900d1a94b6`. Полные legal-хеши и побайтные проверки сохранены в [verification.json](verification.json).

Условия RenderScope не обозначаются как MIT или OSI-approved open source. Исторический JAR 0.1.0 не перепаковывался; прежняя Private-видимость сохранена как датированный снимок, а не обещание будущей приватности проекта.

## Состояние площадок и история

- [x] Историческая Modrinth [Alpha 0.1.0](https://modrinth.com/mod/renderscope/version/YzYFQvOb) и её прежний JAR/hash не заменены версией 0.2.0; прежние Private-снимки сохранены в metadata history.
- [x] Иконки в Modrinth/CurseForge исправлены с полным исходным рисунком; доказательства находятся в `distribution/platforms/` репозитория.
- [x] Forge [Alpha 0.2.0 в Modrinth](https://modrinth.com/mod/renderscope/version/vHpqVjbu) и [Alpha 0.2.0 в CurseForge](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271697) загружены; оба повторно скачанных JAR — 371 686 байт, SHA-256 `1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`, точно совпадают с installed-артефактом.
- [x] Modrinth submission подтверждён сообщением «Your project has been submitted for review!» и статусом «На рассмотрении»; proof `modrinth-moderation-020.png`.
- [x] CurseForge проект 1756581, файл 9271697 имеет статус **Under Review**; сохранены Forge/client/Java21/Minecraft1.20.1/Alpha, EN/RU changelog и автоматическая публикация после approval. Proof `curseforge-moderation-020.png`, remote hash — `curseforge-020-verification.json`.
- [x] Отдельная [NeoForge Alpha 0.2.0 в CurseForge, файл 9271865](https://www.curseforge.com/minecraft/mc-mods/renderscope/files/9271865) имеет **Under Review**. Повторно скачанный JAR — 371 691 байт, SHA-256 `2fd7f289ffd302991e7216d59e37fd24a50d409a6d986108a9f1a96a5eafeb50`, совпадает с локальным NeoForge-артефактом; proof `curseforge-neoforge-020-verification.json`. NeoForge в Modrinth не загружен.
- [x] У CF-файлов Forge 9271697 и NeoForge 9271865 повторно прочитаны **Client** на уровне файла, только соответствующий loader, Java 21, Minecraft 1.20.1, Alpha и автоматическая публикация после approval. Proofs: `curseforge-forge-client-020.png`, `curseforge-neoforge-client-020.png`, `curseforge-loaders-moderation-020.png`; изменение отдельной project-level environment-настройки не заявляется.
- [x] Новое разрешение пользователя на публичность и отправку обоих проектов выполнено; прежнее удержание CF JAR по условию приватности перенесено в историю.
- [ ] Модераторы одобрили проекты и подтверждена состоявшаяся публичная публикация. На текущий момент approval не заявляется.

Авторитетный снимок кабинетов: `distribution/platforms/publication-status.json`. Публичный GitHub и заявки на модерацию Modrinth/CurseForge — разные состояния. Действующие статусы Under Review и предыдущие приватные snapshots записаны отдельно; загрузка и submission не означают approval.
