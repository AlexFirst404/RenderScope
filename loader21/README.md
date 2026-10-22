# Native Forge and NeoForge adapters

`targets.json` contains the exact supported build coordinates, Java version, output JAR and test-result paths. Each target shares the profiler and the inspected Minecraft adapter with `mc21`, while loader initialization and world/HUD callbacks remain native. Build success is separate from installed-client gameplay evidence.

From the repository root with JDK21:

```powershell
.\gradlew.bat -p loader21/forge '-Pminecraft_version=1.21.1' build smokeJar
.\gradlew.bat -p loader21/neoforge '-Pminecraft_version=1.21.1' build smokeJar
```

The unclassified JAR is the distributable, including nested MixinExtras where the loader does not provide it. The `-smoke-harness.jar` is a separate QA mod and must not be distributed as RenderScope. Forge 1.20.5+ and NeoForge use the official runtime namespace; these adapters intentionally do not generate Forge SRG refmaps. NeoForge 1.20.5 uses its published NeoGradle userdev configuration because that beta predates ModDev bundles. Forge has no corresponding 1.20.5 or 1.21.2 target.

API boundaries are explicit:

* Before 1.21.3, Forge publishes `RenderLevelStageEvent`; later Forge uses exact `LevelRenderer.renderLevel` arguments for the camera view and projection matrices.
* Forge 50.2.0 passes the view matrix as the event projection. Forge 51.0.33 and 52.1.0 additionally pass an entity-local pose rather than the full camera view. Target-specific adapters use the active projection and combine the active global view with that local pose. Installed-world tests on Forge 50.2.0 and 51.0.33 caught the rejected labels before these corrections and verify the resulting box and label alignment.
* Forge 50.2.0 bundles Sponge Mixin 0.8.5, which does not recognize `JAVA_21`. That target emits its own classes and Mixins as Java 17 bytecode while retaining a Java 21 compiler and game runtime.
* Forge 1.21.6+ uses EventBus7, including the mod-specific key-registration bus and cancellable key predicate.
* NeoForge 1.21.6+ stage events omit the projection matrix, so its native world adapter also receives the actual render-call matrices.
* Native stage callbacks can retain the camera view in the global model-view stack. The adapter temporarily uses an identity global view while submitting vertices and projecting labels with the explicit event matrix, then restores the caller's stack. This prevents applying the camera transform twice.
* The 1.21.5+ pipeline adapter uses an access transformer corresponding to the shared Fabric access widener. Forge's intermediate remap requires a name-independent selector for the single private pipeline-registration helper.

The loader APIs were inspected in the exact official source/universal artifacts, including [Forge58.1.0 sources](https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.8-58.1.0/forge-1.21.8-58.1.0-sources.jar) and [NeoForge21.8.57 sources](https://maven.neoforged.net/releases/net/neoforged/neoforge/21.8.57/neoforge-21.8.57-sources.jar). Verified vendor caches retain upstream POM/module files and SHA-1 checksums; TLS verification is never disabled.

`scripts/test-native-client.py` prepares an official installer profile and optionally launches it with `--launch`. It checks actual runtime loader/Minecraft identity, the hash of the loaded main JAR and fresh harness output. Gameplay evidence belongs under `distribution/{loader}/{minecraft}/installed/`; missing evidence does not imply a successful runtime test.

The October 22 installed-world checkpoint completed all 26 native scenarios in `validation/world-targets.json`, including the six older Forge/NeoForge 1.20.2–1.20.4 targets. Each scenario produced 28 actual screenshots and passed real entity/block-entity sampling, Full geometry, Timing Only, capture/export privacy, F8, pause-menu and selection checks. The tested JAR hashes are recorded in each installed verification file; this checkpoint does not automatically validate later rebuilt JARs.

Manual inspection of heatmap and inspector screenshots approved 25 targets. Forge 1.21.6 exposed partly occluded world-label glyphs, so its visual approval is withheld. The original screenshots, result, verification and failed-review reason are preserved in `validation/label-occlusion-before/forge-1.21.6/`. A Minecraft 1.21.6 font-depth correction and fresh installed tests are required before claiming a complete visual pass. `scripts/summarize-native-worlds.py` deliberately requires all 26 current artifact hashes and manual reviews before writing a successful aggregate.
