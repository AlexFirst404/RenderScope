# Modern Fabric adapter

This independent Gradle 9.6 project implements the Minecraft render-state pipeline. It shares RenderScope's loader-independent profiler, diagnostics, settings and platform metadata with the older adapters. The 26.x project uses official unobfuscated artifacts and Java 25. The `mapped/` project uses official Mojang mappings for 1.21.9–1.21.11 and emits Java 21 classes; its Gradle/test process uses JDK 25.

Build with JDK 25 from this directory:

```powershell
.\gradlew.bat '-Pminecraft_version=26.3' build installedSmokeProductionJar
```

The source selector has explicit adapters for 26.1, 26.1.1, 26.1.2, 26.2 and 26.3. Selecting a source adapter alone does not mean that version passed a runtime test. Build outputs and test XML are isolated under `build/<minecraft>/`. Current installed evidence belongs under `validation/installed/<minecraft>/`. A successful result must identify the actual loaded primary JAR and match its current hash; compilation or preparation alone does not prove a world pass. Historical development-client results retain their distinct scope.

For the mapped family:

```powershell
.\gradlew.bat -p mapped '-Pminecraft_version=1.21.11' build installedSmokeProductionJar
```

Mapped build outputs are isolated below `mapped/build/<minecraft>/`. All eight Fabric targets passed 65 actual unit tests each and real integrated worlds in **installed production clients**. Each current run has fourteen original framebuffer screenshots, a five-second capture, actual-origin SHA-256 verification and a separate physical review of outlines, labels, Inspector and F1-hidden HUD. [The installed matrix](validation/installed-world-matrix.json) links the exact results; [the visual review](validation/world-visual-review.json) binds reviewed PNG hashes. Previous development-client runs are historical evidence and are not relabelled as installed tests. Use `python verify-build.py --minecraft <version> --require-runtime` to require current matching evidence; omitting that flag checks only packaging/tests.

The profiler intercepts the concrete entity/block-entity renderer's **CPU state extraction** and **CPU state submission** calls separately. A bounded weak identity map carries value-only world identity and the original frame handle between them. It excludes preview entities, virtual block entities and calls outside the actual world pass. Nested calls use the core's exclusive accounting. State associations clear every frame and measurements clear on disconnect/world replacement.

Submission can enqueue deferred rendering work. These measurements do not include the later shared draw execution, terrain, particles or GUI. They are not object GPU timings, draw-call counts or a prediction of the FPS gained by removing a mod. Vertex counts and GPU timing are explicitly unavailable in this adapter; no OpenGL-only query is issued from these hooks.

The native F8/Controls key binding and Pause menu button open a native screen with Overview, Mods, Objects, Instances, Timeline, Heatmap, Capture and Settings. Highlight, heatmap and inspector use measured world instances. Versions 1.21.11 and 26.x use Minecraft's own gizmo extraction. Versions 1.21.9–1.21.10 use real private-buffer line/font draws through native RenderPipelines, with explicit depth behavior and no raw OpenGL state mutation; these outlines do not add the later gizmo fill effect. Reports omit usernames, server/world names, UUIDs, identities and filesystem paths; positions/dimensions require the opt-in setting.

Selecting an instance shows the registry owner's and renderer implementation owner's display names separately. The native wrapping tooltip retains both namespaces, the full implementation class, timing percentiles and actual extraction/submission CPU values. Clicking this strip copies the implementation class; it never exposes an instance UUID.

For a real test, coordinate exclusive GPU use with other running adapters, then run:

```powershell
.\gradlew.bat '-Pminecraft_version=26.3' runClientGameTest
```

The harness creates a real local world and checks actual chest/cow extraction and submission, F8, the added Pause button, native object/instance row drilldown, renderer-class clipboard equality against measured data, the native Highlight button, measured-instance gizmos, heatmap, crosshair inspector, a five-second capture, report privacy, small/wide windows and disconnect cleanup. It saves fourteen screenshots, including actual Inspector and F1-hidden HUD frames, and a result file. The harness is a separate development test mod and is not included in the production JAR. For installed production validation, build `installedSmokeProductionJar` and run `python modern/run-installed-worlds.py --launch` from the repository root. The isolated QA JAR uses real client tick/input APIs, rejects a development classpath and hashes the actual loaded main origin. It is excluded from the primary artifact. Coordinate exclusive graphics-client use before `--launch`; omitting it only prepares official profiles.

RenderScope uses the repository's custom Source Available license. The JAR includes the exact root license and third-party notices.

The HUD hook runs on each actual return path, including native loader early returns, while respecting hidden GUI and open-screen Inspector guards. The QA requires real drawing counters to advance, stop during F1 and resume afterward; a crosshair data lookup alone cannot establish a HUD pass. [Actual Java runtime evidence](validation/actual-runtime-java.json) distinguishes Java21 for mapped clients from Java25 for26.x.
