## RenderScope 0.3.0 — Forge / Minecraft 1.21.5

- Client only; forge 55.1.0, Java 21. No server installation.
- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.
- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.
- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.

- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.

Measurement scope: Version-specific entity/block-entity CPU hooks, including extraction where available. Geometry is limited to observed standard buffers; newer Sodium/Iris native geometry is unavailable. GPU work, terrain, shader execution and shared instancing are not assigned to objects. A vanilla baseline does not establish optional-mod or shader compatibility.

Validation: **66 unit tests**; Current hash-matched world evidence: installed-production
- [Evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/1.21.5/installed/smoke-result.json)

SHA-256: `77b8e506628133d9b22dae610c48e6653b18b398b2d313fac730699b0cfdf3d2` (411565 bytes).

---

## Русский

Клиентская сборка для **Minecraft 1.21.5 / forge 55.1.0 / Java 21**. На сервер мод не нужен.

Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.

Область измерения: Версионные CPU-хуки сущностей и блок-сущностей, включая extraction там, где доступен. Геометрия ограничена наблюдаемыми стандартными буферами; native-геометрия новых Sodium/Iris недоступна. GPU, terrain, выполнение шейдеров и общий instancing не распределяются между объектами. Ванильный прогон не подтверждает совместимость со всеми модами и шейдерами.

Проверено unit-тестов: **66**. Для текущего хеша есть игровой результат: installed-production

RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).
