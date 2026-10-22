## RenderScope 0.3.0 — NeoForge / Minecraft 1.20.1

- Client only; neoforge 47.1.106, Java 21. No server installation.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.

Measurement scope: Entity/block-entity renderer CPU intervals. Version-gated optional integrations include supported Flywheel visual CPU tasks and attributed Mekanism deferred callbacks. Observed immediate-buffer geometry is partial. Shared Flywheel GPU timestamps, when available in Full, stay separate from objects and are not exported as capture GPU timings. Optional-mod support requires its own pinned combination evidence.

Validation: **66 unit tests**; Current hash-matched world evidence: installed-production
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/neoforge/1.20.1/installed/installed-verification.json)

SHA-256: `4210e8bbd1e08ea2451bfb8ef0e93abffd5c3044eb9cdaa7fa6649243bfc0291` (422853 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.20.1 / neoforge 47.1.106 / Java 21**. На сервер мод не нужен.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.

Область измерения: CPU-интервалы рендереров сущностей и блок-сущностей. Версионные интеграции включают поддерживаемые visual CPU-задачи Flywheel и отложенные вызовы Mekanism. Геометрия стандартных буферов наблюдается частично. Общие GPU-метки Flywheel, если доступны в Full, отделены от объектов и не экспортируются как GPU-тайминги capture. Совместимость модов подтверждается отдельно для точного сочетания версий.

Проверено unit-тестов: **66**. Для текущего хеша есть игровой результат: installed-production

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
