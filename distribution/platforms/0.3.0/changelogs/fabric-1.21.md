## RenderScope 0.3.0 — Fabric / Minecraft 1.21

- Client only; fabric 0.19.5, Java 21. No server installation.
- Required Fabric API: **0.102.0+1.21**.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

Measurement scope: Version-specific entity/block-entity CPU hooks, including extraction where available. Geometry is limited to observed standard buffers; newer Sodium/Iris native geometry is unavailable. GPU work, terrain, shader execution and shared instancing are not assigned to objects. A vanilla baseline does not establish optional-mod or shader compatibility.

Validation: **63 unit tests**; World verification is pending for this exact artifact hash.


SHA-256: `762779a95fe2cee88adecc9c1ab6a3f52bb56ab777e1028f84c1a5b9f7543cfa` (406141 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.21 / fabric 0.19.5 / Java 21**. На сервер мод не нужен.

Обязателен Fabric API **0.102.0+1.21**.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. CPU-доли не обещают прирост FPS.

Область измерения: Версионные CPU-хуки сущностей и блок-сущностей, включая extraction там, где доступен. Геометрия ограничена наблюдаемыми стандартными буферами; native-геометрия новых Sodium/Iris недоступна. GPU, terrain, выполнение шейдеров и общий instancing не распределяются между объектами. Ванильный прогон не подтверждает совместимость со всеми модами и шейдерами.

Проверено unit-тестов: **63**. Игровая проверка именно этого хеша ещё не завершена.

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
