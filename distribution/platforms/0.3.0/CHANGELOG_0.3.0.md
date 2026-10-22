# RenderScope 0.3.0

- 67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3.
- Fabric, Forge and NeoForge adapters use their real version-specific rendering APIs.
- Clearer diagnostics connect measured CPU work to a mod, type and concrete world instance.
- Shared renderer implementations retain independent registry-owner attribution.
- Replaced block types at one position retain separate instance/capture costs; Inspector checks the live registry type.
- Native UI, highlight/track, heatmap, Inspector, timeline, capture and private JSON.
- Explicit partial measurement scope: unavailable geometry/GPU work is never invented.

- Fixed Minecraft 1.21.6 world-label depth with a version-specific foreground pass.

- Corrected Forge/NeoForge 1.21.9–1.21.11 resource-pack metadata; installed-client worlds verified.

- Fixed Inspector HUD on Forge early-return paths; actual HUD visibility and F1 hide/restore verified across all 24 Minecraft 1.21.9–26.3 builds.

- Corrected NeoForge block-entity extraction hooks for its Frustum overload; all eight exact Minecraft 1.21.9–26.3 targets verified with real entity/block-entity calls.

Current snapshot: 67/67 exact artifact hashes have real-world evidence; 67/67 have matching manual world/Inspector visual reviews; 67/67 packaged copies match the current audited binaries. 67/67 targets have passed the actual owner-isolation, block-replacement, rolling, capture and exporter XML regressions. Git/raw byte identity for hashed evidence and media: passed. Installed production and development-client scopes are listed per artifact. All exact targets satisfy the local release gate. Platform publication and moderation status are recorded separately.

[Exact dependencies](DEPENDENCIES.md) · [Per-artifact evidence](release-manifest.json)

## Русский

67 отдельных клиентских сборок для 23 стабильных версий Minecraft. Диагностика ведёт от измеренной CPU-работы к конкретному объекту; общие renderer-классы учитываются отдельно для каждого registry owner. Замена блока другим типом в той же позиции сохраняет раздельную стоимость экземпляров и историю capture; Inspector проверяет текущий registry-тип. Сохранены нативный UI, подсветка, tracking, теплокарта, инспектор, timeline, capture и приватный JSON. Недоступные GPU/geometry-метрики не подменяются оценками.

Исправлена глубина мировых меток Minecraft 1.21.6 отдельным проходом переднего текста.

Исправлены resource-pack метаданные Forge/NeoForge 1.21.9–1.21.11; проверены миры установленных клиентов.

Исправлен Inspector HUD при раннем выходе из отрисовки Forge; реальная видимость HUD и скрытие/возврат через F1 проверены на всех 24 сборках Minecraft 1.21.9–26.3.

Исправлены хуки извлечения состояния блок-сущностей NeoForge для перегрузки с Frustum; на всех восьми точных целях Minecraft 1.21.9–26.3 проверены реальные вызовы сущностей и блок-сущностей.

Для текущих хешей есть 67/67 игровых результатов, 67/67 ручных визуальных проверок; совпадает 67/67 упакованных JAR. Установленный клиент и dev-среда различаются в доказательствах. Все цели прошли локальную проверку готовности. Фактическая публикация и модерация платформ учитываются отдельно.

[Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE): бесплатные проекты и изменения разрешены по её условиям; платные содержащие проекты, продажи и платный доступ требуют письменного разрешения.
