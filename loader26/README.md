# Forge / NeoForge adapters for Minecraft 26

Each stable release has its own artifact: 26.1, 26.1.1, 26.1.2, 26.2 and 26.3.
Exact upstream loader versions and build inputs are in
[the shared targets](../docs/compatibility/build-targets.json); actual build and
world evidence is in [the support matrix](../docs/compatibility/SUPPORT-MATRIX.md).

The application and compiler require **JDK 25**. Our native-loader classes use
Java 21 bytecode and `JAVA_21` Mixin compatibility because the official loader's
Sponge Mixin supports that language level. This does not lower Minecraft's Java
25 runtime requirement. The Fabric adapters retain their own verified language
level and runtime.

Shared profiler and diagnostic code lives in `src/main/java/dev/renderscope/core`.
Native loader bootstraps and ownership adapters live here; extraction/submission,
GUI and world drawing reuse the matching API families in `modern/`. Minecraft
26.1 uses the legacy level extraction boundary, 26.2 uses the state extractor and
26.3 additionally uses SDL input. Geometry and per-object GPU work are unavailable
in these adapters; measured CPU preparation and submission remain separate.

```powershell
python scripts/build-all.py --minecraft 26.3 --loader forge --java25 /path/to/jdk25
```

The `smoke` source set is a separate QA mod, excluded from production artifacts.
It creates a real integrated world, exercises native input and widgets, reads
actual profiler snapshots, checks instance highlighting, captures screenshots and
privacy-checks a real report. Its packages and Mixin configuration are disjoint
from the main mod to avoid JPMS split packages. After renaming QA packages, run
`cleanCompileSmokeJava smokeJar` to remove obsolete incremental class outputs.
Use `-Ppython=/absolute/path/to/python` when Python is not on `PATH`.

Installed-client checks use the checksum-verified official installer and
`scripts/test-native-client.py`. Preparing a profile does not count as a world
test. Successful evidence identifies the actual loaded main JAR's SHA-256;
rebuilding an artifact invalidates its previous world result.
