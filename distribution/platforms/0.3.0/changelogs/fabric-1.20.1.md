## RenderScope 0.3.0 — Fabric / Minecraft 1.20.1

- Client only; fabric 0.19.5, Java 21. No server installation.
- Required Fabric API: **0.92.12+1.20.1**.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

Measurement scope: Entity/block-entity renderer CPU intervals. Version-gated optional integrations include supported Flywheel visual CPU tasks and attributed Mekanism deferred callbacks. Observed immediate-buffer geometry is partial. Shared Flywheel GPU timestamps, when available in Full, stay separate from objects and are not exported as capture GPU timings. Optional-mod support requires its own pinned combination evidence.

Validation: **61 unit tests**; Current hash-matched world evidence: installed-production
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/fabric/renderer-compat/iris/installed-verification.json)
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/fabric/renderer-compat/sodium/installed-verification.json)

SHA-256: `8e6ec8658d44a27822e627363f6ca71906283f75f651cea2624c9965e13de21c` (420551 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.20.1 / fabric 0.19.5 / Java 21**. На сервер мод не нужен.

Обязателен Fabric API **0.92.12+1.20.1**.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. CPU-доли не обещают прирост FPS.

Область измерения: CPU-интервалы рендереров сущностей и блок-сущностей. Версионные интеграции включают поддерживаемые visual CPU-задачи Flywheel и отложенные вызовы Mekanism. Геометрия стандартных буферов наблюдается частично. Общие GPU-метки Flywheel, если доступны в Full, отделены от объектов и не экспортируются как GPU-тайминги capture. Совместимость модов подтверждается отдельно для точного сочетания версий.

Проверено unit-тестов: **61**. Для текущего хеша есть игровой результат: installed-production

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
