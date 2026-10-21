# Optional renderer integrations: Minecraft 1.20.1

These adapters observe renderer CPU work and immediate-mode vertex emission. They do not assign terrain batches, shader execution, or shared GPU work to individual mods. An inspected adapter is not a runtime compatibility claim; successful installed-client evidence is required for each tested combination.

| Component | Inspected artifact | Integration |
| --- | --- | --- |
| Sodium, Fabric | 0.5.13; 0.5.12-beta.2 | Successful `BufferBuilder.push` batches and direct delegate vertex completion; ordinary renderer timing remains separate. |
| Embeddium, Forge/NeoForge | 0.3.31+mc1.20.1 | Same native copy boundary, using the destination vertex format stride. |
| Rubidium, Forge/NeoForge | 0.7.1 | Same batch-copy boundary. Test separately from Embeddium. |
| Iris, Fabric | 1.7.6+mc1.20.1 | Public shadow-pass API plus real `SegmentedBufferBuilder` RenderType requests, which bypass vanilla `BufferSource`. |
| Oculus, Forge/NeoForge | 1.8.0 | The same public Iris API and inspected segmented buffer boundary; active shader-pack tests use Complementary Reimagined r5.3. |
| Mekanism Extras, Forge | 1.5.0 | Its standard dispatchers and native Mekanism deferred cube callback are attributed to the addon’s registry and implementation owner. |

The optional Mixin plugin gates external descriptors before they are resolved. Flywheel’s existing adapter requires Minecraft 1.20.1 and Flywheel 1.0.5. Mekanism’s deferred adapter requires the inspected Minecraft 1.20.1 / 10.4.16 family: build 10.4.16.80 publishes `10.4.16` in `mods.toml`. Unknown Minecraft/mod versions disable the optional adapter while ordinary dispatch timing remains available. This matters for Fabric Create builds using older Flywheel 0.6 and for other Minecraft releases that reuse the version string `1.0.5`.

Embeddium itself publishes a compatibility mod ID `rubidium` version 0.7.1. Detection therefore gives Embeddium priority. Installing the real Rubidium JAR alongside Embeddium remains invalid; the preparation/launcher scripts reject that combination.

`RenderingCompatibility.snapshot()` reports installed versions, inspected coverage, actual observed native batch counters, and shader API state. Counters are lifetime diagnostics of successful writes inside real measured renderers. They contain no world coordinates, identities, paths or game objects. Shader API presence alone does not establish that a shader pack or shadow pass was exercised.

## Repeatable isolated tests

`python scripts/prepare-renderer-compat.py` downloads pinned vendor files, verifies SHA-512 and creates separate manifests. It does not launch Minecraft or Gradle. Profiles include Forge Embeddium, Rubidium, Oculus, standalone Mekanism Extras, Fabric Sodium, and Fabric Iris.

After building the production mod and separate compatibility harness, an example Forge run is:

```powershell
python scripts/test-installed-client.py --renderscope build/forge/libs/renderscope-forge-0.3.0+mc1.20.1.jar --harness build/forge/libs/renderscope-forge-0.3.0+mc1.20.1-compat-harness.jar --extra-mod-manifest .tools/perf-compat/profiles/forge-embeddium/mods.json --game-dir run-renderer-compat-embeddium --output distribution/forge/renderer-compat/embeddium --launch
```

Each profile requires its own game/output directory. The launcher checks extra-file SHA-1/SHA-256, actual mod IDs, expected runtime versions, the fresh result and the exact production RenderScope JAR hash. The default eleven diagnostic screenshots remain separate from optional addon screenshots. Tests use charged native cubes, real tank contents, moving Create mechanisms and actual renderer callbacks. No synthetic measurements are injected.

The Oculus profile stages a genuine shader pack and writes its isolated configuration. A successful run must observe an enabled pack and a real API-confirmed shadow pass. A shaders-disabled startup is insufficient. Fabric profiles cannot be passed to the Forge launcher. Iris’s Modrinth dependency metadata references Sodium 0.5.12-beta.2, while the bundled `fabric.mod.json` accepts 0.5.x. The exact stable Sodium 0.5.13 + Iris 1.7.6 pair has now passed the installed production client scenario with active Complementary Reimagined r5.3.

Fabric production runs use the official Mojang client and Fabric Meta profile, with verified client, libraries, native DLLs, assets and mod hashes:

```powershell
python scripts/test-fabric-client.py --minecraft 1.20.1 --loader 0.19.5 --artifact fabric/build/libs/renderscope-fabric-0.3.0+mc1.20.1.jar --harness fabric/build/libs/renderscope-fabric-0.3.0+mc1.20.1-smoke-harness.jar --extra-mod-manifest .tools/perf-compat/profiles/fabric-iris/mods.json --game-dir run-fabric-iris-installed --output distribution/fabric/renderer-compat/iris --launch
```

The Sodium and Iris evidence folders contain `smoke-result.json`, `installed-launch.json`, `installed-verification.json`, a privacy-checked capture export and 28 real screenshots. The harness verifies Full geometry, renderer timing in Timing Only, stopped geometry counters, capture/export privacy, F8, the pause-menu button and submitted world boxes/labels. Iris additionally requires actual shader-buffer requests and a real observed shadow pass. These are exact tested combinations on Minecraft 1.20.1 / Fabric Loader 0.19.5 / Java 21, not support claims for other releases.

The real Sodium test exposed that the cloud-dependent `AFTER_TRANSLUCENT` hook did not draw the overlay. Fabric now uses `WorldRenderEvents.LAST`, the documented final immediate draw with camera view, and the same assertions pass with 14 real boxes. The Iris test separately exposed its replacement buffer source; the optional adapter observes its genuine requests without wrapping or replacing a consumer. [Fabric’s exact 1.20.1 event implementation](https://github.com/FabricMC/fabric/blob/1.20.1/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/mixin/client/rendering/WorldRendererMixin.java).

## Primary sources and inspected binaries

- [Embeddium native buffer source](https://github.com/FiniteReality/embeddium/blob/20.1/forge/src/main/java/me/jellysquid/mods/sodium/mixin/core/render/immediate/consumer/BufferBuilderMixin.java) and [official 0.3.31 metadata](https://api.modrinth.com/v2/version/UTbfe5d1).
- [Sodium 0.5.13 metadata](https://api.modrinth.com/v2/version/OihdIimA), [Rubidium 0.7.1 metadata](https://api.modrinth.com/v2/version/VKligEsm), and the [Rubidium source repository](https://github.com/Asek3/Rubidium).
- [Public Iris API source](https://github.com/IrisShaders/Iris/blob/1.7.2%2B1.20.1/src/main/java/net/irisshaders/iris/api/v0/IrisApi.java), [Iris 1.7.6 artifact metadata](https://api.modrinth.com/v2/version/s5eFLITc), and [Oculus 1.8.0 artifact metadata](https://api.modrinth.com/v2/version/iQ1SwGc3). The API signatures were also checked directly in both exact JARs.
- [Mekanism Extras 1.5.0 source](https://github.com/lostmyself8/Mekanism-Extras/tree/v1.5.0-1.20.1), including its [native deferred energy-cube renderer](https://github.com/lostmyself8/Mekanism-Extras/blob/v1.5.0-1.20.1/src/main/java/com/jerry/mekanism_extras/client/render/tileentity/RenderExtraEnergyCube.java), and [official artifact metadata](https://api.modrinth.com/v2/version/9T6CetkG). The exact project is `lostmyself8/Mekanism-Extras`, Modrinth `HUZkxNTQ`, mod ID `mekanism_extras`; it is not a Fabric addon.
- [Complementary Reimagined r5.3 metadata](https://api.modrinth.com/v2/version/71Hn3myZ).
