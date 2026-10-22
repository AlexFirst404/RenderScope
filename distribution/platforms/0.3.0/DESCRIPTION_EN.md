# RenderScope 0.3.0

![RenderScope](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/brand/renderscope-cover-1600x900.png)

**Find the object behind measured render CPU work.**

RenderScope is a client-side profiler for **23 stable Minecraft Java releases from 1.20.1 through 26.3**, with **67 separate Fabric / Forge / NeoForge targets**. Choose one JAR matching your exact Minecraft version and loader. Forge has no upstream release for 1.20.5 or 1.21.2. The server does not need RenderScope.

Open **F8** or **Esc → RenderScope**. The first page names the mod with the most measured object-render CPU work, marks preliminary data, and helps you reach its heaviest measured instance. Inspect mod/type/instance tables, use Highlight/Track, compare nearby objects in the heatmap, and inspect the object under your crosshair. Record 5/10/30 seconds or manually, then export local JSON.

**Minecraft 26.x needs Java 25; earlier RenderScope targets use Java 21. Fabric also requires the matching Fabric API.** Exact build-pinned loader/API versions are listed in the [download and dependency table](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/platforms/0.3.0/DEPENDENCIES.md). Some pinned upstream loader/API builds are beta; their versions and status are retained. Do not install builds for different loaders together.

The 1.20.1–1.21.8 UI uses **Start**, **Find object**, **Show in world** and **Record 10 s**. The 1.21.9–26.3 UI uses **Overview**, **Show the instance**, **Inspect measured mods** and its Capture tab. English and Russian are included.

**Read the scope before blaming a mod.** CPU intervals are not GPU time or a promised FPS gain from removing a mod. Shared work and parallel visual tasks can overlap. Terrain, particles, GUI, shader execution and unobserved deferred/instanced geometry are outside per-object totals. The render-state adapters for 1.21.9–26.3 measure extraction/submission CPU and explicitly mark geometry/GPU timings unavailable. Full mode only adds metrics the selected adapter can actually observe.

Minecraft 1.20.1 retains version-gated Flywheel visual CPU and Mekanism deferred integrations. Shared Flywheel GPU timestamps are separate, asynchronous, supported-context diagnostics; they are not divided among mechanisms. Optional Sodium/Iris/Embeddium/Rubidium/Oculus/Create/Mekanism combinations need their own [exact evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/OPTIONAL-RENDERER-COMPATIBILITY.md). No newer-version compatibility is implied by a 1.20.1 screenshot or test.

Reports remain in the profile's `renderscope-reports` folder. Positions and dimensions are off by default. Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded; nothing is automatically uploaded.

[Current build/world evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/compatibility/SUPPORT-MATRIX.md) distinguishes **installed production clients** from **development clients**, with exact artifact hashes. A build pass alone is not a world or shader compatibility pass.

Source is available under **RenderScope Source-Available License 1.0**. Personal/private tool use, modification and free inclusion/redistribution are allowed under its terms. Selling the software, including it in paid projects/modpacks/bundles, or charging for access needs prior written permission. [Source and full license](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).

![Measured mod table / Измеренная таблица модов](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/01-create-mekanism-mods.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).


![World heatmap / Теплокарта в мире](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/06-create-mekanism-heatmap.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).


![A selected real instance / Выбранный экземпляр мира](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/04-create-selected-instance.png)

1.20.1 · Forge · installed-production. [Exact evidence / Точная проверка](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
