# Isolated installed Fabric gameplay QA

These test-only artifacts reuse the unchanged `ModernClientSmoke` gameplay assertions through the same native world/input/screenshot adapters as the installed Forge/NeoForge harness. Fabric's client-tick event starts the separate QA mod. No game-test API or synthetic profiler samples are used.

Build without launching Minecraft:

```powershell
modern/gradlew.bat -p modern/mapped '-Pminecraft_version=1.21.9' '-Ppython=C:/path/to/python.exe' installedSmokeProductionJar
modern/gradlew.bat -p modern '-Pminecraft_version=26.3' '-Ppython=C:/path/to/python.exe' installedSmokeProductionJar
python modern/verify-installed-smoke.py
python modern/run-installed-worlds.py
```

The first command family produces a remapped intermediary QA JAR for 1.21.9–1.21.11. The second produces a native unobfuscated QA JAR for the five Minecraft 26 targets. The primary JAR remains unchanged. The verifier checks its frozen SHA, separate class packages, exact Minecraft/Java metadata, required input Mixin/refmap, absence of development game-test APIs, and actual-origin guard implementation.

Only after explicit GPU handover:

```powershell
python modern/run-installed-worlds.py --launch
```

The launcher uses official checksum-verified vanilla/Fabric libraries and the exact build-pinned Fabric API. The isolated game profiles run Java 21 for mapped targets and Java 25 for Minecraft 26. Each result must declare the actual Fabric/Minecraft versions, reject a development classpath, resolve the real primary `ModContainer` origin to one regular JAR, and prove it is the staged requested file before hashing it.

All existing UI, capture, privacy, owner/class-copy, F8/Pause, world highlight, heatmap, crosshair and disconnect assertions remain required. At least 13 distinct screenshot files are required. Geometry and GPU metrics remain explicitly unavailable. A successful process exit without a fresh successful result is a failure.

Preparation records do not establish runtime success. The shared target manifest lists `modern/validation/installed/<Minecraft>/installed-verification.json` before the separate historical development result. The support matrix and publication helper accept it only when the successful wrapper and its hash-linked detail match the actual Minecraft, loader, scope and primary JAR SHA. The runner checks that its output directory matches this manifest path.

After an actual world pass, `python modern/verify-build.py --minecraft 26.3 --require-runtime` checks the same proof and all gameplay assertions, then reports its actual scope. Until that pass exists, the working development-client runner and its truthful evidence remain separate; a matching development result is never labeled installed production. Compiling or preparing this harness does not upgrade a target to world-tested.
