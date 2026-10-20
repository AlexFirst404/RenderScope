## RenderScope 0.2.0 — Flywheel instances

- Measure Create/Flywheel visual CPU creation, frame, tick, update and light tasks against real entity/block-entity instances.
- Select a concrete mechanism and highlight or track it with Flywheel instancing enabled.
- Keep shared Flywheel batch CPU and asynchronous Full-mode GPU timings separate from individual objects.
- Export CPU sources and shared phases, with world positions off by default.
- Preserve Timing Only and optional-mod absence; retain real Mekanism deferred rendering support.
- Include the RenderScope Source-Available License 1.0 and third-party notices in this JAR. Free use, changes and free projects/modpacks are allowed; sales, paid containing projects/modpacks and paid access require prior written permission.

Verified with Minecraft 1.20.1 / Forge 47.4.10 / Create 6.0.8 / Flywheel 1.0.5 / Mekanism 10.4.16.80 in an installed production profile; 36 unit/privacy tests pass. Shared GPU cost and instanced geometry are not assigned to individual mechanisms. Virtual contraption child blocks are not individually attributed in world coordinates.

---

## Русский

В 0.2.0 CPU-задачи Flywheel связываются с конкретными механизмами Create. Выберите экземпляр, включите Highlight/Track и найдите его в мире при работающем instancing. Общая CPU/GPU-нагрузка батча показывается отдельно. Timing Only продолжает измерять время без GPU-запросов. В JSON сохраняются CPU-источники и общие фазы; координаты по умолчанию выключены.

Новая собственная лицензия разрешает изменения и бесплатные проекты/модпаки. Продажа, платные проекты/модпаки с RenderScope и платный доступ требуют предварительного письменного согласия владельца. Точные условия: https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE
