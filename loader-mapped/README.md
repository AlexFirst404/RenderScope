# Forge and NeoForge render-state adapters

These projects reuse the measured CPU extraction/submission hooks, native UI, bounded instance state, diagnostics, capture privacy and world overlays from `modern/`. They do not replace the shared profiler or assert per-object GPU/geometry measurements.

The exact target definitions are in `targets.json`. They describe how to build; runtime support must be established separately. Each artifact has exact Minecraft and loader metadata and is client-side. No server component or network channel is registered.

Forge 1.21.9/1.21.10 follow their inspected official MDKs with ForgeGradle 6 and Java 21. Their local wrapper pins Gradle 8.14.3 and its official SHA-256. Forge 1.21.11 follows its official ForgeGradle 7 MDK, using the shared Gradle 9.6 wrapper on JDK 25 while emitting Java 21 classes. NeoForge uses ModDevGradle 2.0.148 on that shared wrapper and also emits Java 21 classes.

From the repository root, with the appropriate `JAVA_HOME`:

```powershell
.\loader-mapped\forge\gradlew.bat -p loader-mapped/forge '-Pminecraft_version=1.21.10' build
.\modern\gradlew.bat -p loader-mapped/forge7 '-Pminecraft_version=1.21.11' build
.\modern\gradlew.bat -p loader-mapped/neoforge '-Pminecraft_version=1.21.9' build
```

The supported configurations select Forge 59.0.5/60.1.0/61.2.0 and NeoForge 21.9.16-beta/21.10.64/21.11.48. The beta suffix of the 21.9 loader is retained. All outputs and test XML are isolated below the selected project's `build/<minecraft>/` directory. A compilation or packaging pass is not an installed-client pass.

MixinExtras packaging is target-specific. Forge 59's official userdev dependencies do not supply it, so that artifact embeds 0.4.1. Forge 60.1.0 already supplies 0.5.0 and Forge 61 supplies its own runtime; those artifacts use the loader's copy and do not nest a conflicting older version. NeoForge supplies its runtime as well. The Forge 59/60 inspected vendor-config hashes and coordinates are recorded in `validation/mixinextras-runtime-policy.json`.

The loader bootstraps use their actual event APIs for F8 and the additional Pause button. Forge retains an instance `ModList` on these versions. NeoForge 21.9 uses its FML 9 SecureJar module metadata; 21.10/21.11 use the FML 10 module descriptor API. Both ownership paths retain registry and renderer implementation owners separately.

These versions run named Minecraft in Forge/NeoForge production. No Fabric intermediary or Forge SRG refmap is included. For 1.21.9–1.21.10, native RenderPipeline outlines require an Access Transformer. NeoForge uses the exact named method. ForgeGradle 6 transforms methods before final official renaming, so its separate AT opens the methods of the small RenderPipelines registry; its sole private callable helper is registration. This avoids manufacturing version-specific SRG names. The ordinary private-buffer renderer then uses native pipelines, depth behavior and font draws.

Both the primary mod and separate QA mod follow the pinned native loaders' own hidden-pack metadata pattern: individual `pack.mcmeta` uses the **server data** range 88.0 for 1.21.9/1.21.10 and 94.1 for 1.21.11. These numbers are not client asset formats. The loaders synthesize hidden client pack metadata using the actual **client resource** format 69.0 or 75.0. `validation/native-pack-metadata.json` records the exact upstream JAR/class proofs for this delegation, while `validation/resource-pack-formats.json` records the official vanilla client archive hashes and both format families. Two regressions exercise the actual selected Minecraft codecs: emitted native metadata parses for both pack types, while a resource-only range is rejected during the loader's server-pack read. Both artifact verifiers and the shared build audit enforce this distinction.

`python loader-mapped/verify-build.py --loader <forge|neoforge> --minecraft <version>` verifies the selected JAR, Java level, exact target metadata, legal/icon bytes, absence of Fabric classes/refmaps/test harnesses, and selected-version test XML. The tests exercise actual patched Minecraft interception sites and frame/background contracts in addition to the shared profiler/privacy tests.

License: the repository's custom RenderScope Source Available license, embedded byte-for-byte with third-party notices in each artifact.

The separate `smoke` source set builds with `smokeJar`. It adapts the same real-world assertions as the Fabric modern harness, without a Fabric dependency, and is excluded from the production artifact. Its `.modern.smoke` packages are disjoint from the main mod's packages for native Java module isolation. A QA-only mouse callback Invoker supplies actual tooltip hovering and is not shipped with the primary JAR. Native development runs use `-Pnative_smoke runClient` on Forge or `runSmokeClient` on NeoForge. They save exact target/artifact metadata, thirteen screenshots, an actual capture, F8/Pause/row-drilldown/renderer-copy/selection/heatmap/inspector checks and disconnect/privacy results. Coordinate exclusive use of the graphics client before launching either task.

Fresh installed profiles complete vanilla's accessibility onboarding through its actual completion callback before the strict title-screen wait. The harness logs startup screen changes and uses saved, consistent fixture options; unexpected loader screens are not dismissed.

For installed testing, the generic native launcher stages the primary JAR and separate harness. Setting `renderscope.modern.smoke.environment=installed-production` makes the harness locate the actually loaded main JAR through the loader's `ModList`, require the staged file to match, and hash that actual origin. A requested input file alone cannot establish an installed pass. Use `verify-build.py --loader <loader> --minecraft <version> --require-runtime --runtime-evidence <result.json>` to verify the result, loaded origin, exact loader version, artifact bytes and screenshots. A development-client result remains explicitly labelled as that scope.

`targets.json` declares the exact separate `harnessArtifact` for the generic launcher. `python scripts/run-native-matrix.py --manifest loader-mapped/targets.json` from the repository root prepares the six official installed profiles without starting Minecraft. Adding `--launch` performs serial real-world testing and therefore requires the exclusive graphics-client slot. `python loader-mapped/verify-smoke.py` verifies all six QA artifacts and explicitly rejects packages shared with the primary mod, preventing native Java module split-package failures.

All six official profiles have completed verified installer/library/asset preparation; `validation/installed-preparation.json` records those results. Preparation does not establish a world-test pass. The six installed runtime results remain pending until their separate successful, matching `smoke-result.json` evidence exists.
