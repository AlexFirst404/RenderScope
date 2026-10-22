# RenderScope 0.3.0

- 67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3.
- Fabric, Forge and NeoForge adapters use their real version-specific rendering APIs.
- Clearer diagnostics connect measured CPU work to a mod, type and concrete world instance.
- Shared renderer implementations retain independent registry-owner attribution.
- Replaced block types at one position retain separate instance/capture costs; Inspector checks the live registry type.
- Native UI, highlight/track, heatmap, Inspector, timeline, capture and private JSON.
- Explicit partial measurement scope: unavailable geometry/GPU work is never invented.

Current snapshot: 8/67 exact artifact hashes have real-world evidence; 67/67 packaged copies match the current audited binaries. 67/67 targets have passed the actual owner-isolation, block-replacement, rolling, capture and exporter XML regressions. Git/raw byte identity for hashed evidence and media: passed. Installed production and development-client scopes are listed per artifact. DRAFT: this preparation is not a public upload, moderation approval or an assertion that all worlds have passed.

[Exact dependencies](DEPENDENCIES.md) · [Per-artifact evidence](release-manifest.json)

## Русский

67 отдельных клиентских сборок для 23 стабильных версий Minecraft. Диагностика ведёт от измеренной CPU-работы к конкретному объекту; общие renderer-классы учитываются отдельно для каждого registry owner. Замена блока другим типом в той же позиции сохраняет раздельную стоимость экземпляров и историю capture; Inspector проверяет текущий registry-тип. Сохранены нативный UI, подсветка, tracking, теплокарта, инспектор, timeline, capture и приватный JSON. Недоступные GPU/geometry-метрики не подменяются оценками.

Для текущих хешей есть 8/67 игровых результатов; совпадает 67/67 упакованных JAR. Установленный клиент и dev-среда различаются в доказательствах. ЧЕРНОВИК: это локальная подготовка, а не подтверждение публикации или одобрения платформами.

[Полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE): бесплатные проекты и изменения разрешены по её условиям; платные содержащие проекты, продажи и платный доступ требуют письменного разрешения.

[All exact downloads](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/0.3.0/README.md)
