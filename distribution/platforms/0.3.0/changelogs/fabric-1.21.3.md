## RenderScope 0.3.0 — Fabric / Minecraft 1.21.3

- Client only; fabric 0.19.5, Java 21. No server installation.
- Required Fabric API: **0.114.1+1.21.3**.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.

Measurement scope: Version-specific entity/block-entity CPU hooks, including extraction where available. Geometry is limited to observed standard buffers; newer Sodium/Iris native geometry is unavailable. GPU work, terrain, shader execution and shared instancing are not assigned to objects. A vanilla baseline does not establish optional-mod or shader compatibility.

Validation: **68 unit tests**; Current hash-matched world evidence: installed-production
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/mc21/fabric/1.21.3/installed-verification.json)
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/mc21/fabric/1.21.3/smoke-result.json)

SHA-256: `a38de4d7ed19444676a99b70b21d27f9318fb12f48a125dea42f56a9c30ebeed` (407633 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.21.3 / fabric 0.19.5 / Java 21**. На сервер мод не нужен.

Обязателен Fabric API **0.114.1+1.21.3**.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.

Область измерения: Версионные CPU-хуки сущностей и блок-сущностей, включая extraction там, где доступен. Геометрия ограничена наблюдаемыми стандартными буферами; native-геометрия новых Sodium/Iris недоступна. GPU, terrain, выполнение шейдеров и общий instancing не распределяются между объектами. Ванильный прогон не подтверждает совместимость со всеми модами и шейдерами.

Проверено unit-тестов: **68**. Для текущего хеша есть игровой результат: installed-production

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
