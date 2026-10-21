# RenderScope · Fabric / Java 21 adapters

This independent Loom project shares the profiler, diagnostics, report exporter, settings, translations and loader-neutral client code with the repository root. Minecraft API changes are isolated in the adapter sources here.

## Build

Use JDK 21. For Minecraft 1.20.5 through 1.21.4, use the repository's Gradle 8.8 wrapper from the repository root in PowerShell:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\gradlew.bat -p mc21 '-Pminecraft_version=1.21.1' test build
```

Minecraft 1.21.5 through 1.21.8 require the module's Gradle 8.14.3 wrapper and Loom 1.10.5. The corresponding Fabric API releases declare the newer Loom toolchain in their own metadata:

```powershell
.\mc21\gradlew.bat -p mc21 '-Pminecraft_version=1.21.8' test build
```

The exact Minecraft version is embedded in the filename and Fabric metadata. Build output stays separate under `mc21/build/<minecraft-version>/libs/`. Install one main JAR matching the Minecraft version, together with Fabric Loader and the corresponding Fabric API. The source JAR is not an installable client mod.

| Target | Fabric API | Adapter family |
| --- | --- | --- |
| 1.21.1 | 0.116.17+1.21.1 | `src/main/java` |
| 1.21 | 0.102.0+1.21 | `src/main/java` |
| 1.20.6 | 0.100.8+1.20.6 | `src/pre121/java` overrides |
| 1.20.5 | 0.97.8+1.20.5 | `src/pre121/java` overrides |
| 1.21.2 | 0.106.1+1.21.2 | `src/state/java` overrides |
| 1.21.3 | 0.114.1+1.21.3 | `src/state/java` overrides |
| 1.21.4 | 0.119.4+1.21.4 | `src/state/java` overrides |
| 1.21.5 | 0.128.2+1.21.5 | `src/pipeline/java` overrides |
| 1.21.6 | 0.128.2+1.21.6 | `src/gui/java` and pipeline overrides |
| 1.21.7 | 0.129.0+1.21.7 | `src/gui/java` and pipeline overrides |
| 1.21.8 | 0.136.1+1.21.8 | `src/gui/java` and pipeline overrides |

All use Fabric Loader 0.19.5, official Mojang mappings and Java 21 bytecode. The table lists build targets. `port-status.json` distinguishes successful compilation/tests from a hash-verified installed production client run; a target without `installed_production_passed` remains unverified in game.

## Minecraft API boundaries

- In 1.21/1.21.1, `WorldOverlay` owns a `ByteBufferBuilder`; ordinary and packed `BufferBuilder.addVertex` paths both pass through the measured `beginVertex` allocation.
- In 1.20.5/1.20.6, the adapter retains the older `BufferBuilder` / `endVertex` path and Fabric's `tickDelta` callback.
- Starting with 1.21.2, entity renderers prepare a pooled render-state object. The adapter measures the actual extraction and renderer calls separately, associating the state with value-only instance metadata and the captured profiler frame. Exact private overload descriptors identify the dispatcher methods containing those calls; starting in 1.21.5, rendering moves to the overload accepting a render state. Stale frames are rejected by the shared profiler. The association is bounded and uses weak identity keys.
- Starting with 1.21.5, world outlines use native `RenderPipeline` definitions with depth-aware and through-wall variants. The adapter does not mutate OpenGL state. A narrow access widener exposes Minecraft's existing line-pipeline factory and snippet. The block entity invocation also includes the actual camera-position argument.
- Starting with 1.21.6, the screen uses native pipeline-based texture drawing and the new tooltip-component API.
- The screen retains the current beginner overview and all detailed tabs. Its background and scroll signatures, ResourceLocation construction and EditBox lifecycle follow the selected Minecraft API.
- Custom screen content follows the native `renderBackground` callback. Minecraft 1.20.5–1.21.5 invokes it from `Screen.render`; 1.21.6+ invokes it from `renderWithTooltip`. Native widgets and tooltips retain their own rendering order, and the background blur runs once.
- `ClientRuntime` adapts the moved key-binding screen class. The Fabric entrypoint handles screen/key/HUD/world events; loader metadata remains behind `PlatformAccess`.
- Entity and block entity calls wrap the actual individual renderer invocation, with value-only instance identity and immutable snapshots. The central profiler and diagnostic rules are shared rather than copied.
- World overlays use Fabric's `LAST` event, whose API contract provides the camera-transformed world matrix for immediate framebuffer drawing. RenderScope flushes only its own private buffer source.
- Billboard orientation follows the selected Minecraft renderer: 1.20.5/1.20.6 retain the older negative X scale, while 1.21+ use positive X. The shader-based adapters restore depth writes after outline drawing; the 1.21/1.21.1 text foreground uses Minecraft's own 0.03 local-Z separation above its background effect.

The complete UI and allowlist report serializer are regenerated from their shared implementations with `py -3 mc21/scripts/sync-ui.py`; only verified API differences and this adapter's narrower coverage text are substituted. This keeps beginner diagnostics, preliminary low-FPS actions, advanced tabs and privacy fixes consistent across adapter families.

## Installed client check

Add `remapSmokeJar` to the build command to create a separate, development-only test mod. The main JAR contains no smoke code. The generated scenario preserves the shared checks for actual per-instance timings, observed vertices and RenderTypes, Timing Only, a five-second capture, report privacy, F8 and pause-menu integration, world boxes, Inspector and 28 real screenshots. World creation, registry access, frame callbacks and screenshot signatures follow the selected Minecraft version.

`scripts/test-fabric-client.py` at the repository root can prepare and launch the production JAR with this separate harness. It verifies official client/library checksums, the runtime Minecraft/Fabric versions and the SHA-256 of the actual measured RenderScope JAR. Use a dedicated `run-fabric-smoke-<version>` directory and place evidence under `distribution/mc21/fabric/<version>/`. Launches require `--launch`; preparation alone does not run the game.

After a completed check, run `py -3 mc21/scripts/verify-artifacts.py <versions> --write --require-runtime`. The verifier also checks the actual exported capture, screenshot files and installed launch/result hashes. Optional third-party profiles are a separate compatibility check.

For RenderScope 0.3.0, all eleven targets above passed 59 tests each and the installed production scenario with 28 screenshots per target. `port-status.json` records each actual JAR hash, capture hash, runtime version and evidence path; `targets.json` supplies the exact build and evidence paths for the repository's build matrix.

The optional Flywheel, Mekanism deferred and alternative fast-vertex adapters inspected on Minecraft 1.20.1 are not enabled here. Their Minecraft-specific descriptors are omitted from this module's mixin configuration, and the shared integration whitelist checks the Minecraft version. Ordinary dispatcher CPU timing remains available; custom or instanced paths can bypass geometry counters. No third-party compatibility matrix or per-object GPU attribution is claimed for these targets.

The mod is client-only, requires no server component, and retains the report privacy defaults and custom source-available license from the repository root.

## API sources

- [Fabric: Minecraft 1.21 and 1.21.1 changes](https://fabricmc.net/2024/05/31/121.html)
- [Fabric: Minecraft 1.20.5 and 1.20.6 changes](https://fabricmc.net/2024/04/19/1205.html)
- [Official Fabric API Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
- [Official Fabric Loader Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml)
- [Official Fabric Loom Maven metadata](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml)

Exact method descriptors were also checked against the corresponding Minecraft JAR produced with official Mojang mappings. A successful build does not establish that mixins, overlays or inputs work in an actual game session; that requires a separate client run.
