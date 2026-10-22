## RenderScope 0.3.0 — NeoForge / Minecraft 26.1.1

- Client only; neoforge 26.1.1.15-beta, Java 25. No server installation.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.

Measurement scope: Exclusive CPU render-state extraction and submission, grouped by source. Later deferred feature batches, geometry and GPU timings are unavailable per object. Zero geometry fields do not mean an object emits no geometry. Terrain, shader execution and shared instancing work are not assigned to objects.

Validation: **65 unit tests**; World verification is pending for this exact artifact hash.


SHA-256: `19cf887be9c82b6caa30bf433c95a220afdb2bca79a79a916d02abff10db2750` (155900 bytes).

---

## Русский

Клиентская сборка для **Minecraft 26.1.1 / neoforge 26.1.1.15-beta / Java 25**. На сервер мод не нужен.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.

Область измерения: Раздельные эксклюзивные CPU-интервалы извлечения render state и submission. Поздние отложенные feature batches, геометрия и GPU-тайминги отдельных объектов недоступны. Нулевые поля геометрии не означают отсутствия геометрии. Terrain, выполнение шейдеров и общий instancing не распределяются между объектами.

Проверено unit-тестов: **65**. Игровая проверка именно этого хеша ещё не завершена.

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
