## RenderScope 0.3.0 — Forge / Minecraft 1.21.11

- Client only; forge 61.2.0, Java 21. No server installation.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.

- Corrected the native resource-pack metadata for this Minecraft version; verified through an installed-client world.
- Fixed a missing Inspector HUD on Forge's early-return render path. F1 hides and restores the overlay; verified actual HUD draw counts and framebuffer frames.

Measurement scope: Exclusive CPU render-state extraction and submission, grouped by source. Later deferred feature batches, geometry and GPU timings are unavailable per object. Zero geometry fields do not mean an object emits no geometry. Terrain, shader execution and shared instancing work are not assigned to objects.

Validation: **67 unit tests**; Current hash-matched world evidence: installed-production
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/1.21.11/installed/smoke-result.json)

SHA-256: `7f156bef163d91e989f65a1174153c510fe42812158495876fe11636120f7987` (155893 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.21.11 / forge 61.2.0 / Java 21**. На сервер мод не нужен.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.

- Исправлены нативные метаданные resource pack для этой версии Minecraft; проверено в мире установленного клиента.
- Исправлено исчезновение Inspector HUD при раннем выходе из отрисовки Forge. F1 скрывает и возвращает оверлей; проверены реальные вызовы HUD и кадры игры.

Область измерения: Раздельные эксклюзивные CPU-интервалы извлечения render state и submission. Поздние отложенные feature batches, геометрия и GPU-тайминги отдельных объектов недоступны. Нулевые поля геометрии не означают отсутствия геометрии. Terrain, выполнение шейдеров и общий instancing не распределяются между объектами.

Проверено unit-тестов: **67**. Для текущего хеша есть игровой результат: installed-production

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
