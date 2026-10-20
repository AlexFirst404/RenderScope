# RenderScope 0.2.0

![RenderScope](brand/renderscope-cover-1600x900.png)

**Find the entity or block entity behind an expensive renderer call.**

RenderScope brings a native Minecraft profiler into the game. Start with measured cost by mod, drill into a registered object type, select an individual instance, and highlight its bounding box in the world.

**Minecraft 1.20.1 · Forge 47.4.10 · Java 21 · Client-side only**

## Find it in the world

Press **F8**, or use **Esc → RenderScope**. The key binding is configurable under **Options → Controls → RenderScope**.

Use **Mods** to find expensive measured registry-owner groups, then **Objects** to inspect entities and block entities. Each object has per-frame average, P95, peak, calls, instances, renderer class and observed RenderTypes. Select an instance to **Highlight**, **Track**, or **Copy Position**.

Turn on the **world heatmap** for comparative green/yellow/red boxes. Filter entities and block entities separately, set distance and minimum cost, limit highlighted objects, toggle labels, or enable through-wall display. The **crosshair inspector** displays real profiler information for the measured object you are aiming at.

## Understand a frame spike

The **Overview** shows FPS, frame-time percentiles, 1% low, worst frame, measured scene counts and top offenders. The **Timeline** keeps approximately 30 seconds of real frame samples. Switch between FPS, frame duration, total measured renderer duration, entity duration and block entity duration, then click a frame to inspect its attribution.

The default rolling window is five seconds. Change it in Settings, choose **Timing Only** or **Full**, or disable measurement when you no longer need it. Collection works with the interface closed.

## Screenshots

Captured from a real local scene with vanilla objects. Measured values vary with your world and hardware.

![RenderScope overview](gallery/01-overview.png)

![Object details and individual instances](gallery/04-object-detail.png)

![Relative render-cost heatmap in the world](gallery/09-world-heatmap.png)

![Real measured statistics in the crosshair inspector](gallery/10-crosshair-inspector.png)

![Heatmap animation recorded inside Minecraft](gallery/heatmap-demo.gif)

## Capture and share useful evidence

Record **5 / 10 / 30 seconds** or stop a capture manually. Its summary shows average FPS, 1% low, worst frame and measured offenders. **Export JSON** saves a local report to your Minecraft profile's `renderscope-reports` folder.

Reports contain Minecraft/Forge/RenderScope versions, installed mod names and versions, and recorded statistics. They exclude player names, server addresses, world names, chat, UUIDs and absolute paths. Coordinates and dimensions are off by default and require an explicit settings toggle. RenderScope does not upload reports.

## Measurement scope

RenderScope measures **CPU-side elapsed time inside individual renderer calls and supported visual tasks**. Parallel visual work is summed from actual execution intervals and can exceed elapsed frame time. Shared Flywheel GPU timing is a separate metric; per-object GPU time, per-mod VRAM and complete rendering coverage are unavailable. Terrain, particles, GUI, independent item-renderer calls and separate shader passes are outside this release's attribution scope.

Full mode observes vertices emitted through the standard BufferBuilder and RenderType requests through BufferSource. Custom pipelines may bypass these counters. **Estimated vertex data** describes observed geometry; it is not GPU upload bytes or GPU memory. Registry and renderer implementation owners remain separate; individual mixin contributors cannot be resolved from a modified class alone.

Optional **Mekanism 10.4.16.80** support follows translucent work queued by a measured renderer and times its actual deferred callback against the originating instance. The queue itself does not create timing samples. Timing Only retains callback timing without geometry counters; Mekanism is not a required dependency.

**New in 0.2.0: Flywheel 1.0.5 visual CPU profiling.** Visual creation, frame, tick, update and light work is associated with real world instances, including Create mechanisms using the active instancing backend. Source breakdowns distinguish ordinary renderer calls from visual CPU tasks; instance highlights and heatmaps use the same measured world objects. Shared batch submission has a separate CPU phase.

In **Full**, asynchronous OpenGL timestamp queries also measure shared Flywheel batch GPU intervals when supported. The live GPU view uses completed sampled Flywheel frames from approximately five seconds; results are collected later without blocking after each object. **Timing Only** disables GPU queries. CPU sources and shared phases are exported; the live GPU diagnostic is not a GPU capture. Instanced vertices and GPU time are not divided among individual objects. Virtual block entities inside contraptions remain outside separate world-position attribution, and RenderScope does not force a different backend.

The **0.2.0 installed production Forge scenario passed** with **Create 6.0.8 / Flywheel 1.0.5 / Ponder 1.0.91 and Mekanism 10.4.16.80** with rotating motor/shaft/cogwheel and an animated press. It verified actual per-instance visual CPU sources with instancing enabled, shared CPU/GPU diagnostics, Full/Timing Only, individual selections, heatmap, private capture, visual removal/recreation and world-disconnect cleanup. This is a pinned scene, not a general rendering compatibility matrix.

![Real Create and Mekanism verification with installed production JARs](compatibility-installed/01-create-mekanism-mods.png)

![Flywheel visual CPU sources for a real mechanism](compatibility-installed/08-create-instanced-shaft-detail.png)

![A rotating shaft selected with instancing enabled](compatibility-installed/09-create-instanced-shaft-selected.png)

## Installation

Install **Forge 47.4.10 for Minecraft 1.20.1**, run the profile on **Java 21**, and put `renderscope-forge-0.2.0+mc1.20.1.jar` into the client's `mods` directory. Runtime MixinExtras support is bundled in the main JAR. NeoForge has a separate build; use only one RenderScope JAR matching the profile's loader.

**Install on the client only. The server does not need RenderScope.** No custom networking component is required. English and Russian UI translations are included.
