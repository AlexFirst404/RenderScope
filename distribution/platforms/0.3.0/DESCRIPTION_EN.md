# RenderScope 0.3.0

![RenderScope pixel-art logo](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/brand/renderscope-logo-384x384.png)

## English

**Find a measured render cost. Follow it to one object in your world.**

RenderScope connects object-render CPU work to a mod, a type and a specific entity or block entity. It provides **67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3**, with Fabric, Forge and NeoForge adapters. The server does not need the mod.

### Install

1. Choose **one JAR matching your exact Minecraft version and loader**. Do not install builds for different loaders together. Forge has no upstream release for 1.20.5 or 1.21.2.
2. Use **Java 25 for Minecraft 26.x**, or **Java 21 for earlier RenderScope targets**.
3. For Fabric, also install the matching **Fabric API**. Use the [exact loader and API table](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/platforms/0.3.0/DEPENDENCIES.md); pinned upstream beta versions remain identified.
4. Put the JAR in your client profile's `mods` folder. Enter a world and press **F8**, or open **Esc → RenderScope**. F8 can be rebound in Controls.

### Find the object

1. Read the first page's largest **measured object CPU** contributor. Early results are marked preliminary.
2. Open **Mods → Objects**, select a type, then inspect an instance. Registry ownership and renderer implementation ownership stay visible separately.
3. Use **Highlight** to see its box, **Track** to keep it marked, **Heatmap** to compare nearby measured objects, or **Inspect** for the object under your crosshair.

In **1.20.1–1.21.8**, **Track** also shows a direction and distance HUD, and the UI offers **Find object / Show in world**. In **1.21.9–26.3**, **Track** keeps the selected object marked in the world; the UI offers **Show the instance / Inspect measured mods**. English and Russian are included.

### Metrics and recording

- Read FPS/frame-time percentiles, measured CPU averages/P95/peaks, calls and the live timeline.
- Record **5 / 10 / 30 seconds** or stop manually, then export JSON to the profile's `renderscope-reports` folder.
- **Timing Only** records supported CPU intervals. **Full** adds only extended metrics the adapter can really observe.

CPU time is **not GPU time or a promised FPS improvement from removing a mod**. Parallel visual CPU intervals may overlap. Terrain, particles, GUI, shader execution and unobserved shared/deferred work are outside per-object totals.

For **1.21.9–26.3**, object metrics measure render-state extraction/submission CPU; per-object geometry and GPU timings are unavailable. A zero geometry field does not prove that the object emits no geometry.

**1.20.1** retains version-gated Flywheel visual CPU and Mekanism deferred integrations. Supported shared Flywheel GPU timestamps are asynchronous, separate from objects and live-only; capture exports CPU data. Check [exact optional-mod and shader evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/OPTIONAL-RENDERER-COMPATIBILITY.md); one tested combination does not establish support for every version.

### Privacy

Reports stay local; nothing is automatically uploaded. **Positions and dimensions are off by default.** Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded. Installed mod names/versions and measured CPU data are included.

[Build and world evidence](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/docs/compatibility/SUPPORT-MATRIX.md) records exact JAR hashes and distinguishes installed production clients from development clients. A build pass alone is not a gameplay or shader compatibility pass.

### Source and license

**RenderScope Source-Available License 1.0** allows personal/private tool use, modification and free redistribution/inclusion under its terms. Selling the mod/code, including it in paid projects/modpacks/bundles, or charging for access requires prior written permission. [Source and full license](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/LICENSE).

## Real footage · archive

**Archive: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** These actual installed-client recordings show Create 6.0.8 / Flywheel 1.0.5 with the indirect backend and Mekanism 10.4.16.80. They illustrate the workflow, not current 0.3.0 validation or compatibility on other targets. The GIFs use 16 real captured frames each; rounded corners do not alter the displayed measurements.

![Selected rotating Create shaft · archived 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-shaft-live.gif)

*Selected rotating Create shaft · archived 0.2.0.*

![Selected moving Create press · archived 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/create-press-live.gif)

*Selected moving Create press · archived 0.2.0.*

![Relative measured object CPU heatmap · archived 0.2.0](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/presentation/heatmap.png)

*Relative measured object CPU heatmap · archived 0.2.0.*

[Recorded provenance](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/presentation/presentation-manifest.json).

## Current 0.3.0 evidence

- [Measured mod table](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/01-create-mekanism-mods.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [World heatmap](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/06-create-mekanism-heatmap.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
- [A selected real instance](https://raw.githubusercontent.com/AlexFirst404/RenderScope/v0.3.0/distribution/forge/renderer-compat/embeddium/04-create-selected-instance.png) — 1.20.1 / Forge / installed-production. [JSON](https://github.com/AlexFirst404/RenderScope/blob/v0.3.0/distribution/forge/renderer-compat/embeddium/installed-verification.json).
