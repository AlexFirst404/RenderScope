"""Keep the loader-independent smoke scenario identical while adapting event registration."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / 'src/smoke/java/dev/renderscope/smoke/SmokeTest.java').read_text(encoding='utf-8')
source = source.replace('package dev.renderscope.smoke;', 'package dev.renderscope.fabric.smoke;')
source = source.replace('import net.minecraftforge.common.MinecraftForge;\n', '')
source = source.replace('import net.minecraftforge.event.TickEvent;\n', '')
source = source.replace('import net.minecraftforge.fml.common.Mod;',
    'import net.fabricmc.api.ClientModInitializer;\nimport net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;')
source = source.replace('@Mod("renderscope_smoke")\npublic final class SmokeTest {',
    'public final class FabricSmokeTest implements ClientModInitializer {')
start = source.index('    public SmokeTest() {')
end = source.index('    public static void tick()', start)
source = source[:start] + '''    @Override public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LOGGER.info("Fabric development smoke harness enabled; isolated world {}", WORLD);
    }

''' + source[end:]
source = source.replace('activeLoader.name().equals("Forge") || activeLoader.name().equals("NeoForge")',
    'activeLoader.name().equals("Forge") || activeLoader.name().equals("NeoForge") || activeLoader.name().equals("Fabric")')
source = source.replace('RESULTS.addProperty("runtimeLoaderVerified", true);', '''RESULTS.addProperty("runtimeLoaderVerified", true);
                    RESULTS.addProperty("minecraftVersion", net.minecraft.SharedConstants.getCurrentVersion().getName());
                    RESULTS.addProperty("environment", System.getProperty("renderscope.smoke.environment", "development"));
                    var primaryOrigin = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("renderscope")
                            .orElseThrow().getOrigin().getPaths();
                    if (primaryOrigin.size() == 1 && Files.isRegularFile(primaryOrigin.get(0))) {
                        var sha = java.security.MessageDigest.getInstance("SHA-256");
                        try (var stream = Files.newInputStream(primaryOrigin.get(0))) {
                            byte[] buffer = new byte[65536];
                            for (int read; (read = stream.read(buffer)) != -1;) sha.update(buffer, 0, read);
                        }
                        RESULTS.addProperty("primaryJarSha256", java.util.HexFormat.of().formatHex(sha.digest()));
                    }''')
source = source.replace('import dev.renderscope.client.ClientRuntime;', '''import dev.renderscope.client.ClientRuntime;
import dev.renderscope.client.integration.IntegrationVersions;
import dev.renderscope.client.integration.FastVertexMetrics;
import dev.renderscope.client.integration.RenderingCompatibility;
import dev.renderscope.client.integration.ShaderPasses;
import dev.renderscope.client.integration.ShaderBufferMetrics;''')
source = source.replace('    private static LoaderInfo activeLoader;', '''    private static LoaderInfo activeLoader;
    private static JsonObject expectedOptionalMods = new JsonObject();
    private static long batchesBeforeTiming;
    private static long delegateVerticesBeforeTiming;
    private static long shaderRequestsBeforeTiming;
    private static final String STRICT_GEOMETRY_POLICY = "strict-native-v1";
    private static final String MODERN_IRIS_POLICY = "iris-sodium-1.21.8-timing-shadow-v1";
    private static boolean modernIrisTimingPolicy;''')
source = source.replace('require(entity, "No real entity renderer samples");', '''RESULTS.add("warmingPerformance", GSON.toJsonTree(snapshot.performance()));
                    RESULTS.add("warmingInstances", GSON.toJsonTree(snapshot.instances()));
                    RESULTS.add("warmingRenderingCompatibility", GSON.toJsonTree(RenderingCompatibility.snapshot()));
                    require(entity, "No real entity renderer samples");''')
source = source.replace('RESULTS.addProperty("loaderVersion", activeLoader.version());', '''RESULTS.addProperty("loaderVersion", activeLoader.version());
                    expectedOptionalMods = JsonParser.parseString(System.getProperty("renderscope.compat.expectedMods", "{}")).getAsJsonObject();
                    for (var entry : expectedOptionalMods.entrySet()) require(entry.getValue().getAsString()
                            .equals(IntegrationVersions.installed().get(entry.getKey())), "Wrong/missing optional Fabric mod " + entry.getKey());
                    RESULTS.add("expectedOptionalMods", expectedOptionalMods);
                    String profile = System.getProperty("renderscope.compat.profile", "baseline");
                    RESULTS.addProperty("compatibilityProfile", profile);
                    String policy = System.getProperty("renderscope.compat.geometryPolicy", STRICT_GEOMETRY_POLICY);
                    require(STRICT_GEOMETRY_POLICY.equals(policy) || MODERN_IRIS_POLICY.equals(policy), "Unknown QA geometry policy");
                    modernIrisTimingPolicy = MODERN_IRIS_POLICY.equals(policy);
                    if (modernIrisTimingPolicy) {
                        require("1.21.8".equals(net.minecraft.SharedConstants.getCurrentVersion().getName())
                                && "fabric-iris-1.21.8".equals(profile)
                                && expectedOptionalMods.size() == 2
                                && expectedOptionalMods.has("sodium") && expectedOptionalMods.has("iris")
                                && "0.7.2+mc1.21.8".equals(expectedOptionalMods.get("sodium").getAsString())
                                && "1.9.6+mc1.21.8".equals(expectedOptionalMods.get("iris").getAsString())
                                && Boolean.getBoolean("renderscope.compat.requireShaders"), "Partial-geometry QA policy requires its exact profile");
                        require(!IntegrationVersions.supportsFastVertices(IntegrationVersions.installed())
                                && !IntegrationVersions.supportsShaderBuffers(IntegrationVersions.installed()),
                                "Policy is obsolete: production now claims this native geometry ABI");
                    }
                    RESULTS.addProperty("geometryPolicy", policy);''')
source = source.replace('if ((!entity || !blockEntity || !geometry) && tick - since < 500) return;', '''boolean shaderReady = !Boolean.getBoolean("renderscope.compat.requireShaders")
                            || (ShaderPasses.state().shaderPackEnabled() && ShaderPasses.state().shadowDetectionAvailable()
                            && ShaderPasses.observedShadowPass());
                    if ((!entity || !blockEntity || (!geometry && !modernIrisTimingPolicy) || !shaderReady) && tick - since < 500) return;''')
source = source.replace('require(geometry, "Full mode did not observe vertices and RenderType requests");', '''if (!modernIrisTimingPolicy) require(geometry, "Full mode did not observe vertices and RenderType requests");''')
source = source.replace('RESULTS.addProperty("fullModeGeometryAndRenderTypes", true);', '''RESULTS.addProperty("fullModeGeometryAndRenderTypes", geometry);
                    if (modernIrisTimingPolicy) {
                        RESULTS.addProperty("fullModeTimingWithPartialGeometry", true);
                        JsonObject coverage = new JsonObject();
                        coverage.addProperty("status", "unavailable-uninspected-native-abi");
                        coverage.addProperty("sodiumNativeVerticesAvailable", false);
                        coverage.addProperty("irisNativeRenderTypeRequestsAvailable", false);
                        coverage.addProperty("reason", "Sodium 0.7.2 and Iris 1.9.6 native buffer adapters are not enabled on Minecraft 1.21.8; zero is not total geometry.");
                        coverage.addProperty("observedStandardVerticesAndRenderTypes", geometry);
                        coverage.addProperty("observedStandardVerticesPerFrame", snapshot.performance().verticesPerFrame());
                        RESULTS.add("nativeGeometryCoverage", coverage);
                    }
                    RESULTS.add("renderingCompatibilityFull", GSON.toJsonTree(RenderingCompatibility.snapshot()));
                    if (expectedOptionalMods.has("sodium") && !expectedOptionalMods.has("iris")) {
                        require(IntegrationVersions.supportsFastVertices(IntegrationVersions.installed())
                                && FastVertexMetrics.observedBatchCalls() > 0, "No actual measured Sodium native batches in Full mode");
                        RESULTS.addProperty("actualSodiumNativeBatchesMeasured", true);
                    }
                    if (Boolean.getBoolean("renderscope.compat.requireShaders")) {
                        require(ShaderPasses.state().shaderPackEnabled() && ShaderPasses.state().shadowDetectionAvailable()
                                && ShaderPasses.observedShadowPass(), "Active Iris shadow pass was not exercised");
                        RESULTS.addProperty("actualShaderShadowPassObserved", true);
                        RESULTS.add("actualShaderState", GSON.toJsonTree(ShaderPasses.state()));
                        if (!modernIrisTimingPolicy) {
                            require(ShaderBufferMetrics.observedRequests() > 0, "No measured Iris native buffer RenderType requests");
                            RESULTS.addProperty("actualShaderBufferRequestsMeasured", true);
                        }
                    }''')
source = source.replace('runtime.settings().mode = ProfilerSettings.MeasurementMode.TIMING_ONLY;', '''batchesBeforeTiming = FastVertexMetrics.observedBatchCalls();
                    delegateVerticesBeforeTiming = FastVertexMetrics.observedDelegateVertices();
                    shaderRequestsBeforeTiming = ShaderBufferMetrics.observedRequests();
                    RESULTS.addProperty("nativeBatchesBeforeTimingOnly", batchesBeforeTiming);
                    RESULTS.addProperty("nativeDelegateVerticesBeforeTimingOnly", delegateVerticesBeforeTiming);
                    RESULTS.addProperty("shaderBufferRequestsBeforeTimingOnly", shaderRequestsBeforeTiming);
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.TIMING_ONLY;''')
source = source.replace('RESULTS.addProperty("timingOnlyFallback", true);', '''RESULTS.addProperty("timingOnlyFallback", true);
                    require(FastVertexMetrics.observedBatchCalls() == batchesBeforeTiming
                            && FastVertexMetrics.observedDelegateVertices() == delegateVerticesBeforeTiming
                            && ShaderBufferMetrics.observedRequests() == shaderRequestsBeforeTiming,
                            "Timing Only continued collecting native vertex geometry");
                    RESULTS.addProperty("nativeGeometryStoppedInTimingOnly", true);
                    RESULTS.add("renderingCompatibilityTimingOnly", GSON.toJsonTree(RenderingCompatibility.snapshot()));''')
source = source.replace('RESULTS.addProperty("worldOverlayBoxesSubmitted", runtime.overlay().renderedWorldBoxes());', '''RESULTS.addProperty("worldOverlayBoxesSubmitted", runtime.overlay().renderedWorldBoxes());
                    if (modernIrisTimingPolicy) {
                        Class<?> bridge = Class.forName("dev.renderscope.client.integration.IrisLinePipelines");
                        Object binding = bridge.getMethod("state").invoke(null);
                        RESULTS.add("irisLinePipelineBinding", GSON.toJsonTree(binding));
                        require((int) binding.getClass().getMethod("registeredPipelines").invoke(binding) == 2,
                                "Private line pipelines were not registered with the actual Iris public API");
                    }''')
source = source.replace('require(runtime.overlay().renderedWorldBoxes() > 0, "Heatmap passed no real objects to world rendering");', '''if (modernIrisTimingPolicy) {
                        RESULTS.add("heatmapPerformanceAfterWarmup", GSON.toJsonTree(runtime.profiler().snapshot().performance()));
                        RESULTS.add("heatmapShaderState", GSON.toJsonTree(ShaderPasses.state()));
                        RESULTS.addProperty("heatmapWaitedTicks", tick - since);
                        Class<?> bridge = Class.forName("dev.renderscope.client.integration.IrisLinePipelines");
                        RESULTS.add("heatmapPipelineBindingAfterWarmup", GSON.toJsonTree(bridge.getMethod("state").invoke(null)));
                        // First-use shader compilation can expire the two-second rolling window.
                        // Wait for actual new-frame objects; the unchanged assertions below must still pass.
                        if ((runtime.overlay().renderedWorldBoxes() == 0 || runtime.overlay().renderedWorldLabels() == 0)
                                && tick - since < 200) return;
                    }
                    require(runtime.overlay().renderedWorldBoxes() > 0, "Heatmap passed no real objects to world rendering");''')

target = root / 'fabric/src/smoke/java/dev/renderscope/fabric/smoke/FabricSmokeTest.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(source, encoding='utf-8', newline='\n')
print(target.relative_to(root))
