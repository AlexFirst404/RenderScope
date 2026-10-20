# RenderScope 0.2.0

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

The primary timer measures **CPU-side elapsed time around individual renderer calls and supported visual tasks**. Parallel visual tasks contribute their actual execution intervals; their sum can exceed elapsed frame time. This CPU timer is separate from GPU execution measurements. This release profiles **EntityRenderer, BlockEntityRenderer and supported Flywheel visual CPU callbacks**, rather than every part of Minecraft rendering: terrain, particles, GUI and standalone shader passes are outside its mod-attribution scope.

**Timing Only** is the default. **Full** additionally observes standard BufferBuilder vertices and BufferSource RenderType requests. Alternative render pipelines can bypass these counters. “Estimated vertex data” is an estimate of observed geometry, not GPU uploads or VRAM.

An optional **Mekanism 10.4.16.80** integration measures real deferred translucent callbacks and attributes their CPU time and observed geometry to the world instance that queued them. Timing Only keeps these timing measurements. Mekanism is not required to use RenderScope.

**New in 0.2.0: Flywheel 1.0.5 visual CPU profiling.** Visual creation, frame, tick, update and light work is associated with real world instances, including Create mechanisms using the active instancing backend. Source breakdowns distinguish ordinary renderer calls from visual CPU tasks; instance highlights and heatmaps use the same measured world objects. Shared batch submission has a separate CPU phase.

In **Full**, asynchronous OpenGL timestamp queries also measure shared Flywheel batch GPU intervals when supported. The live GPU view uses completed sampled Flywheel frames from approximately five seconds; results are collected later without blocking after each object. **Timing Only** disables GPU queries. CPU sources and shared phases are exported; the live GPU diagnostic is not a GPU capture. Instanced vertices and GPU time are not divided among individual objects. Virtual block entities inside contraptions remain outside separate world-position attribution, and RenderScope does not force a different backend.

The **0.2.0 installed production Forge scenario passed** with **Create 6.0.8 / Flywheel 1.0.5 / Ponder 1.0.91 and Mekanism 10.4.16.80** with rotating motor/shaft/cogwheel and an animated press. It verified actual per-instance visual CPU sources with instancing enabled, shared CPU/GPU diagnostics, Full/Timing Only, individual selections, heatmap, private capture, visual removal/recreation and world-disconnect cleanup. This is a pinned scene, not a general rendering compatibility matrix.

![Create and Mekanism in the installed Forge production profile](compatibility-installed/01-create-mekanism-mods.png)

![Flywheel visual CPU sources for a real mechanism](compatibility-installed/08-create-instanced-shaft-detail.png)

![A rotating shaft selected with instancing enabled](compatibility-installed/09-create-instanced-shaft-selected.png)

Mod totals use registry ownership; renderer class ownership is shown separately. Changes injected into another mod's class cannot be precisely attributed to individual mixin authors.

## Install

Use **Minecraft 1.20.1, Forge 47.4.10 and Java 21**. Place `renderscope-forge-0.2.0+mc1.20.1.jar` in your Forge client profile's `mods` folder. Required MixinExtras runtime support is bundled. NeoForge has a separate build; install only one RenderScope JAR matching your profile's loader.

**No server installation is required.** RenderScope sends no custom profiling packets and can be used while playing on a server without RenderScope. It does not optimize or modify the selected world objects.

## Local reports, positions off by default

Exports are saved in `renderscope-reports` inside your Minecraft profile. They include versions, installed mod names/versions and recorded metrics. Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded. Coordinates and dimensions are included only if you explicitly enable **Include world positions**.

Reports are not uploaded automatically.
