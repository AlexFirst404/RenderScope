## RenderScope 0.2.0 — NeoForge / Minecraft 1.20.1

- Client-side build for **NeoForge 47.1.106**, Minecraft **1.20.1** and **Java 21**. The server does not need RenderScope.
- Profile entity and block-entity CPU renderer calls, inspect individual instances, and use Highlight/Track, the world heatmap and crosshair inspector.
- Keep Timing Only and Full modes; Full observes standard BufferBuilder geometry and RenderType requests. These values are not per-object GPU time or VRAM.
- Export explicit CPU work sources and capture statistics to local JSON, with world positions off by default.
- Bundle RenderScope Source-Available License 1.0 and third-party notices. Changes and free projects/modpacks are allowed under its terms; sales, paid containing projects/modpacks and paid access require prior written permission.

Validation: **36 unit/privacy tests** and a real **NeoForge 47.1.106 client baseline with vanilla objects**, including renderer measurements, world overlays, both modes, capture and private export. The separate installed Create/Flywheel/Mekanism scene was tested with the **Forge** artifact; it does not establish NeoForge compatibility with those mods.

---

## Русский

Клиентская сборка RenderScope 0.2.0 для **Minecraft 1.20.1 / NeoForge 47.1.106 / Java 21**. Установка на сервер не нужна.

Измеряйте CPU-вызовы рендереров сущностей и блок-сущностей, выбирайте конкретный экземпляр и используйте Highlight/Track, теплокарту и инспектор под прицелом. Работают Timing Only и Full; наблюдаемая геометрия не выдаётся за GPU-время или VRAM. В локальный JSON входят CPU-источники и статистика capture; координаты по умолчанию выключены.

Проверены **36 unit/privacy-тестов** и настоящий NeoForge-клиент с vanilla-объектами: измерения, world overlays, оба режима, capture и приватный экспорт. Installed-сцена Create/Flywheel/Mekanism проверена отдельно на **Forge** и не считается проверкой совместимости этих модов с NeoForge.

Собственная source-available лицензия разрешает изменения и бесплатные проекты/модпаки. Продажа, платные содержащие проекты/модпаки и платный доступ требуют предварительного письменного разрешения. Точные условия: https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE
