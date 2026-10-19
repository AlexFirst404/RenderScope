# RenderScope

![RenderScope](brand/renderscope-cover-1600x900.png)

**Follow expensive renderer calls all the way to an object in your world.**

RenderScope is a client-side render profiler for **Minecraft 1.20.1 / Forge 47.4.10**. See which registered objects account for measured renderer time, open their individual instances, and highlight the one you want to investigate directly in the world.

## From a slow scene to a specific instance

Open **F8** or **Esc → RenderScope**, choose a mod, inspect an entity or block entity type, and select an instance by position. **Highlight** draws a temporary bounding box; **Track** keeps the selection active; **Copy Position** copies its coordinates.

The profiler continues collecting while the screen is closed. You can also enable **Inspect** to read the measured object under your crosshair, or use a **world heatmap** to compare the cost of nearby measured instances. Green, yellow and red reflect relative cost in the current candidate scene, including a red zone for the most expensive 5%.

## Inside the profiler

- **Overview:** FPS, frame time, 1% low, P50/P95/P99, worst frame and current measured offenders.
- **Mods:** searchable, sortable tables with render time, measured-render share, calls and instance counts.
- **Objects:** entities and block entities with per-frame averages, P95, peak, instances, renderer implementation and observed RenderTypes.
- **Timeline:** a live 30-second history; click a recorded frame to see its actual measured offenders and worst instance.
- **Capture:** record 5, 10 or 30 seconds, or stop manually, then inspect the summary and export JSON.
- **Settings:** Timing Only or Full mode, rolling window, optional thread CPU timing, heatmap filters and report privacy.

English and Russian UI are included. Native Minecraft buttons and scrollable tables adapt to the GUI scale.

## In game

These screenshots were captured from an actual local world with vanilla objects. Reported costs depend on the scene and hardware.

![Live performance overview](gallery/01-overview.png)

![Object details and measured instances](gallery/04-object-detail.png)

![Relative render-cost heatmap in the world](gallery/09-world-heatmap.png)

![Crosshair inspector with real measured object statistics](gallery/10-crosshair-inspector.png)

![RenderScope heatmap recorded inside Minecraft](gallery/heatmap-demo.gif)

## What the numbers mean

The primary timer measures **CPU-side elapsed time around individual world renderer calls**. It does not measure GPU execution time. This release profiles **EntityRenderer and BlockEntityRenderer**, rather than every part of Minecraft rendering: terrain, particles, GUI and standalone shader passes are outside its mod-attribution scope.

**Timing Only** is the default. **Full** additionally observes standard BufferBuilder vertices and BufferSource RenderType requests. Alternative render pipelines can bypass these counters. “Estimated vertex data” is an estimate of observed geometry, not GPU uploads or VRAM.

Mod totals use registry ownership; renderer class ownership is shown separately. Changes injected into another mod's class cannot be precisely attributed to individual mixin authors.

## Install

Use **Minecraft 1.20.1, Forge 47.4.10 and Java 21**. Place `renderscope-forge-0.1.0+mc1.20.1.jar` in your Forge client profile's `mods` folder. Required MixinExtras runtime support is bundled. NeoForge has a separate build; install only one RenderScope JAR matching your profile's loader.

**No server installation is required.** RenderScope sends no custom profiling packets and can be used while playing on a server without RenderScope. It does not optimize or modify the selected world objects.

## Local reports, positions off by default

Exports are saved in `renderscope-reports` inside your Minecraft profile. They include versions, installed mod names/versions and recorded metrics. Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded. Coordinates and dimensions are included only if you explicitly enable **Include world positions**.

Reports are not uploaded automatically.
