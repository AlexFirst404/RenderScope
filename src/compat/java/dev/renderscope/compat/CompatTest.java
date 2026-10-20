package dev.renderscope.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.renderscope.client.ClientRuntime;
import dev.renderscope.client.LoaderInfo;
import dev.renderscope.client.FlywheelGpuTimers;
import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.client.RenderHooks;
import dev.renderscope.client.integration.FlywheelHooks;
import dev.renderscope.client.ui.RenderScopeScreen;
import dev.renderscope.core.AggregateMetric;
import dev.renderscope.core.CaptureReport;
import dev.renderscope.core.InstanceKey;
import dev.renderscope.core.InstanceMetric;
import dev.renderscope.core.MeasurementSource;
import dev.renderscope.core.ObjectKind;
import dev.renderscope.core.ProfilerSnapshot;
import dev.renderscope.core.Statistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs real mod renderers in an isolated fixture world. No synthetic profiler calls or timings. */
@Mod("renderscope_compat")
public final class CompatTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Compatibility");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WORLD = "RenderScope-Create-Mekanism-Compatibility";
    private static final List<String> MODS = List.of("create", "mekanism");
    private static final BlockPos MOTOR = new BlockPos(-8, 64, -3);
    private static final BlockPos SHAFT = new BlockPos(-7, 64, -3);
    private static final BlockPos COGWHEEL = new BlockPos(-5, 64, -3);
    private static final BlockPos PRESS_MOTOR = new BlockPos(-8, 66, 4);
    private static final BlockPos PRESS = new BlockPos(-7, 66, 4);
    private static final Map<String, BlockPos> MECHANISMS = Map.of("motor", MOTOR, "shaft", SHAFT, "cogwheel", COGWHEEL, "press", PRESS);
    private static final BlockPos ENERGY_CUBE = new BlockPos(4, 64, -3);
    private static final Map<String, BlockPos> TARGETS = Map.of("create", new BlockPos(-4, 64, 1), "mekanism", new BlockPos(4, 64, 1));
    private static final List<BlockPos> TANKS = List.of(TARGETS.get("create"), new BlockPos(-2, 64, 1), TARGETS.get("mekanism"), new BlockPos(6, 64, 1));
    private static final Map<String, String> FIXTURE_TYPES = new ConcurrentHashMap<>();
    private static final JsonObject RESULT = new JsonObject();
    private static final JsonArray IMAGES = new JsonArray();
    private static final Queue<CaptureImage> IMAGE_QUEUE = new ArrayDeque<>();
    private static final Queue<Throwable> ERRORS = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger IMAGE_WRITES = new AtomicInteger();
    private static final long STARTED = System.nanoTime();
    private static volatile boolean sceneReady;
    private static volatile int animationUpdates;
    private static volatile float serverShaftRpm;
    private static volatile boolean shaftRemoved;
    private static volatile boolean shaftRestored;
    private static float lastClientPressOffset = Float.NaN;
    private static int pressHeadOffsetChanges;
    private static int shaftSpeedChanges;
    private static float lastClientShaftSpeed;
    private static Phase phase = Phase.BOOT;
    private static int ticks;
    private static int since;
    private static boolean finished;
    private static Path output;
    private static LoaderInfo loader;
    private static InstanceKey selected;
    private static String originalBackend;
    private static ForgeConfigSpec.ConfigValue<String> backendConfig;
    private static long deferredBeforeTiming;
    private static long queuedBeforeTiming;
    private static long renderedBeforeTiming;
    private static long visualFrameBeforeTiming;
    private static long visualTickBeforeTiming;

    private enum Phase {
        BOOT, WORLD, DEFAULT_FULL, DEFAULT_TIMING, FALLBACK_FULL, RESTORE_FULL, CAPTURE,
        MODS_SCREEN, CREATE_LIST, CREATE_DETAIL, MEKANISM_LIST, MEKANISM_DETAIL,
        CREATE_SELECTION, MEKANISM_SELECTION, SHAFT_LIST, SHAFT_DETAIL, SHAFT_SELECTION,
        MOTOR_SELECTION, PRESS_SELECTION, HEATMAP, CAPTURE_SCREEN,
        REMOVE_SHAFT, RESTORE_SHAFT, SESSION_CLEANUP, FINISH
    }

    public CompatTest() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick();
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) frame();
        });
    }

    public static void tick() {
        if (finished) return;
        ticks++;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (output == null) {
                Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                output = Path.of(System.getProperty("renderscope.compat.output",
                        game.getParent().resolve("distribution/forge/compatibility").toString())).toAbsolutePath();
                Files.createDirectories(output);
                RESULT.addProperty("startedAt", Instant.now().toString());
                RESULT.addProperty("evidence", "Real integrated-server fixtures and client renderer calls; no synthetic measurements");
            }
            if (!ERRORS.isEmpty()) throw new IllegalStateException("Compatibility operation failed", ERRORS.remove());
            require(System.nanoTime() - STARTED < 600_000_000_000L, "Timed out during " + phase);
            ClientRuntime runtime = ClientRuntime.current();
            if (runtime == null) return;
            if (sceneReady && mc.level != null && !shaftRemoved) observeClientMotion(mc);
            if (sceneReady && age() >= 20 && ticks % 20 == 0 && mc.getSingleplayerServer() != null) {
                MinecraftServer server = mc.getSingleplayerServer();
                boolean high = (ticks / 20) % 2 == 0;
                server.execute(() -> {
                    try { animateScene(server, high); }
                    catch (Throwable failure) { ERRORS.add(failure); }
                });
            }
            switch (phase) {
                case BOOT -> {
                    if (mc.screen == null || mc.getOverlay() != null) return;
                    loader = LoaderInfo.current();
                    require(loader.name().equals("Forge"), "Compatibility run must use ordinary Forge, got " + loader);
                    require(loader.version().equals(System.getProperty("renderscope.expectedLoaderVersion", loader.version())), "Wrong Forge version");
                    JsonArray installed = new JsonArray();
                    for (String mod : MODS) {
                        var info = ModList.get().getModContainerById(mod).orElseThrow(() -> new IllegalStateException("Missing required mod " + mod)).getModInfo();
                        JsonObject entry = new JsonObject();
                        entry.addProperty("id", mod); entry.addProperty("name", info.getDisplayName()); entry.addProperty("version", info.getVersion().toString());
                        installed.add(entry);
                    }
                    RESULT.add("testedMods", installed);
                    RESULT.addProperty("loader", loader.name()); RESULT.addProperty("loaderVersion", loader.version());
                    String environment = System.getProperty("renderscope.compat.environment", "development");
                    RESULT.addProperty("environment", environment);
                    Path primaryArtifact = ModList.get().getModFileById("renderscope").getFile().getFilePath();
                    if (Files.isRegularFile(primaryArtifact)) {
                        RESULT.addProperty("primaryJarSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(primaryArtifact))));
                        RESULT.addProperty("primaryArtifactKind", "jar");
                    } else {
                        RESULT.addProperty("primaryArtifactKind", "development directory");
                        require(environment.equals("development"), "Installed compatibility run did not load RenderScope from a regular JAR");
                    }
                    mc.options.onboardAccessibility = false;
                    mc.options.tutorialStep = TutorialSteps.NONE;
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    mc.getToasts().clear();
                    mc.options.pauseOnLostFocus = false;
                    mc.options.renderDistance().set(5); mc.options.framerateLimit().set(120); mc.options.guiScale().set(2);
                    mc.options.bobView().set(false); mc.options.gamma().set(1.0); mc.options.hideGui = false;
                    mc.resizeDisplay();
                    runtime.settings().profiling = true;
                    runtime.settings().cpuTiming = true;
                    runtime.settings().sampleWindowSeconds = 2;
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.FULL;
                    runtime.settings().heatmap = false; runtime.settings().inspect = false;
                    runtime.settings().throughWalls = false; runtime.settings().showLabels = true;
                    runtime.settings().minimumCostMs = 0; runtime.settings().highlightTimeoutSeconds = 0;
                    runtime.settings().includeWorldPositions = false;
                    runtime.saveSettings();
                    mc.setScreen(new TitleScreen());
                    if (mc.getLevelSource().levelExists(WORLD)) mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
                    else mc.createWorldOpenFlows().createFreshLevel(WORLD,
                            new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                            new WorldOptions(20261020L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                                    .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
                    advance(Phase.WORLD);
                }
                case WORLD -> {
                    if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        try { constructScene(server); sceneReady = true; }
                        catch (Throwable failure) { ERRORS.add(failure); }
                    });
                    advance(Phase.DEFAULT_FULL);
                }
                case DEFAULT_FULL -> {
                    if (!sceneReady || age() < 140) return;
                    mc.setScreen(null);
                    ProfilerSnapshot snapshot = runtime.profiler().snapshot();
                    if (!ready(snapshot, true) && age() < 600) return;
                    require(serverShaftRpm != 0 && clientShaftSpeed(mc) != 0, "Create fixture is not actually rotating");
                    require(animationUpdates >= 3, "Fluid levels did not update in the real world");
                    require(flywheelOn(), "Default Flywheel backend is not enabled on this GPU");
                    RESULT.addProperty("defaultFlywheelBackend", backendName());
                    RESULT.addProperty("observedCreateShaftRpm", serverShaftRpm);
                    RESULT.addProperty("realFluidAnimationUpdates", animationUpdates);
                    RESULT.add("defaultFull", checkMeasurements(snapshot, true));
                    RESULT.add("defaultInstancedVisualsFull", checkVisualMeasurements(snapshot, true));
                    checkSharedFlywheelPhases(snapshot);
                    FlywheelGpuTimers.Snapshot gpu = FlywheelGpuTimers.snapshot();
                    require(gpu.available() && gpu.sampledFrames() > 0 && gpu.completedBatches() > 0 && gpu.averageMs() > 0,
                            "Full mode has no completed real asynchronous Flywheel GPU timer queries: " + gpu.status());
                    require(gpu.pendingBatches() <= 64, "Asynchronous GPU query ring exceeded its bound");
                    RESULT.add("defaultFlywheelGpuFull", GSON.toJsonTree(gpu));
                    RESULT.addProperty("defaultFlywheelVisualFrameCalls", FlywheelHooks.measuredFrameCalls());
                    RESULT.addProperty("defaultFlywheelVisualTickCalls", FlywheelHooks.measuredTickCalls());
                    RESULT.addProperty("defaultFlywheelVisualUpdateCalls", FlywheelHooks.measuredUpdateCalls());
                    RESULT.addProperty("defaultFlywheelTrackedVisuals", FlywheelHooks.trackedVisuals());
                    RESULT.addProperty("defaultFlywheelSharedBatchCalls", FlywheelHooks.sharedBatchCalls());
                    require(FlywheelHooks.measuredFrameCalls() > 0 && FlywheelHooks.measuredTickCalls() > 0,
                            "Actual Flywheel visual frame/tick callbacks were not measured");
                    visualFrameBeforeTiming = FlywheelHooks.measuredFrameCalls();
                    visualTickBeforeTiming = FlywheelHooks.measuredTickCalls();
                    require(pressHeadOffsetChanges >= 3 && shaftSpeedChanges >= 2, "Native Create mechanism animations did not update on the client");
                    RESULT.addProperty("realPressHeadOffsetChanges", pressHeadOffsetChanges);
                    RESULT.addProperty("realShaftSpeedChanges", shaftSpeedChanges);
                    require(RenderHooks.measuredDeferredCalls() > 0, "No actual Mekanism deferred renderer callbacks were measured");
                    require(RenderHooks.queuedDeferredCalls() > 0 && RenderHooks.renderedDeferredCalls() > 0,
                            "Charged Mekanism cube did not traverse its actual deferred queue");
                    require(RenderHooks.measuredDeferredCalls() <= RenderHooks.renderedDeferredCalls(), "Attributed callback count exceeds actual render attempts");
                    InstanceMetric cube = fixtureInstanceAt(snapshot, "mekanism", ENERGY_CUBE);
                    require(cube.stats().verticesPerFrame() > 0 && !cube.stats().renderTypes().isEmpty(), "Charged Mekanism energy cube deferred geometry was not attributed");
                    RESULT.add("deferredEnergyCubeStatistics", GSON.toJsonTree(cube.stats()));
                    RESULT.addProperty("defaultDeferredRendererCalls", RenderHooks.measuredDeferredCalls());
                    RESULT.addProperty("defaultQueuedDeferredCalls", RenderHooks.queuedDeferredCalls());
                    RESULT.addProperty("defaultRenderedDeferredCalls", RenderHooks.renderedDeferredCalls());
                    deferredBeforeTiming = RenderHooks.measuredDeferredCalls();
                    queuedBeforeTiming = RenderHooks.queuedDeferredCalls();
                    renderedBeforeTiming = RenderHooks.renderedDeferredCalls();
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.TIMING_ONLY; runtime.saveSettings();
                    advance(Phase.DEFAULT_TIMING);
                }
                case DEFAULT_TIMING -> {
                    if (age() < 85) return;
                    ProfilerSnapshot snapshot = runtime.profiler().snapshot();
                    RESULT.add("defaultTimingOnly", checkMeasurements(snapshot, false));
                    RESULT.add("defaultInstancedVisualsTimingOnly", checkVisualMeasurements(snapshot, false));
                    checkSharedFlywheelPhases(snapshot);
                    FlywheelGpuTimers.Snapshot gpu = FlywheelGpuTimers.snapshot();
                    require(gpu.status().equals("disabled") && gpu.completedBatches() == 0 && gpu.pendingBatches() == 0,
                            "Timing Only retained GPU query work");
                    RESULT.add("defaultFlywheelGpuTimingOnly", GSON.toJsonTree(gpu));
                    require(FlywheelHooks.measuredFrameCalls() > visualFrameBeforeTiming && FlywheelHooks.measuredTickCalls() > visualTickBeforeTiming,
                            "Timing Only stopped actual Flywheel visual frame/tick CPU measurements");
                    RESULT.addProperty("timingOnlyFlywheelVisualFrameCalls", FlywheelHooks.measuredFrameCalls() - visualFrameBeforeTiming);
                    RESULT.addProperty("timingOnlyFlywheelVisualTickCalls", FlywheelHooks.measuredTickCalls() - visualTickBeforeTiming);
                    require(RenderHooks.measuredDeferredCalls() > deferredBeforeTiming, "Timing Only stopped measuring Mekanism deferred callbacks");
                    require(RenderHooks.queuedDeferredCalls() > queuedBeforeTiming && RenderHooks.renderedDeferredCalls() > renderedBeforeTiming,
                            "Mekanism deferred queue stopped in Timing Only");
                    RESULT.addProperty("timingOnlyDeferredRendererCalls", RenderHooks.measuredDeferredCalls() - deferredBeforeTiming);
                    RESULT.addProperty("timingOnlyQueuedDeferredCalls", RenderHooks.queuedDeferredCalls() - queuedBeforeTiming);
                    RESULT.addProperty("timingOnlyRenderedDeferredCalls", RenderHooks.renderedDeferredCalls() - renderedBeforeTiming);
                    setFlywheelOff(mc);
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.FULL; runtime.saveSettings();
                    advance(Phase.FALLBACK_FULL);
                }
                case FALLBACK_FULL -> {
                    if (age() < 90) return;
                    require(!flywheelOn(), "Flywheel fallback did not activate");
                    ProfilerSnapshot snapshot = runtime.profiler().snapshot();
                    RESULT.add("fallbackFull", checkMeasurements(snapshot, true));
                    AggregateMetric shaft = snapshot.objects().stream().filter(value -> value.id().equals(FIXTURE_TYPES.get("shaft")))
                            .findFirst().orElseThrow(() -> new IllegalStateException("No fallback Create shaft renderer samples"));
                    require(shaft.stats().verticesPerFrame() > 0, "Create fallback shaft emitted no observed geometry");
                    RESULT.addProperty("fallbackFlywheelBackend", backendName());
                    RESULT.addProperty("fallbackKineticVerticesPerFrame", shaft.stats().verticesPerFrame());
                    restoreFlywheel(mc);
                    advance(Phase.RESTORE_FULL);
                }
                case RESTORE_FULL -> {
                    if (age() < 85) return;
                    require(flywheelOn(), "Default Flywheel backend was not restored");
                    checkMeasurements(runtime.profiler().snapshot(), true);
                    checkVisualMeasurements(runtime.profiler().snapshot(), true);
                    runtime.startCapture(5);
                    advance(Phase.CAPTURE);
                }
                case CAPTURE -> {
                    if (age() < 110 || runtime.profiler().snapshot().capture().active()) return;
                    CaptureReport capture = runtime.profiler().lastCapture();
                    require(capture != null && capture.frames() > 0 && capture.durationSeconds() >= 4.8, "Capture did not finish normally");
                    for (String mod : MODS) require(capture.mods().stream().anyMatch(value -> value.id().equals(mod) && value.stats().calls() > 0), "Capture omitted " + mod);
                    verifyExport(mc, runtime);
                    RESULT.addProperty("captureFrames", capture.frames()); RESULT.addProperty("captureDurationSeconds", capture.durationSeconds());
                    runtime.open(null); clickTab("mods");
                    advance(Phase.MODS_SCREEN);
                }
                case MODS_SCREEN -> {
                    if (age() < 20) return;
                    image("01-create-mekanism-mods.png", () -> openObject("create"));
                    advance(Phase.CREATE_LIST);
                }
                case CREATE_LIST -> {
                    if (age() < 16) return;
                    require(mc.screen.mouseClicked(30, 119, 0), "Create object row did not open");
                    advance(Phase.CREATE_DETAIL);
                }
                case CREATE_DETAIL -> {
                    if (age() < 18) return;
                    image("02-create-fluid-tank-detail.png", () -> openObject("mekanism"));
                    advance(Phase.MEKANISM_LIST);
                }
                case MEKANISM_LIST -> {
                    if (age() < 16) return;
                    require(mc.screen.mouseClicked(30, 119, 0), "Mekanism object row did not open");
                    advance(Phase.MEKANISM_DETAIL);
                }
                case MEKANISM_DETAIL -> {
                    if (age() < 18) return;
                    image("03-mekanism-fluid-tank-detail.png", () -> selectFixture("create"));
                    advance(Phase.CREATE_SELECTION);
                }
                case CREATE_SELECTION -> {
                    if (age() < 35) return;
                    checkSelection(runtime, "create");
                    image("04-create-selected-instance.png", () -> selectFixture("mekanism"));
                    advance(Phase.MEKANISM_SELECTION);
                }
                case MEKANISM_SELECTION -> {
                    if (age() < 35) return;
                    checkSelection(runtime, "mekanism");
                    image("05-mekanism-selected-instance.png", () -> openObject("shaft"));
                    advance(Phase.SHAFT_LIST);
                }
                case SHAFT_LIST -> {
                    if (age() < 16) return;
                    require(mc.screen.mouseClicked(30, 119, 0), "Create shaft object row did not open");
                    advance(Phase.SHAFT_DETAIL);
                }
                case SHAFT_DETAIL -> {
                    if (age() < 18) return;
                    image("08-create-instanced-shaft-detail.png", () -> selectMechanism("shaft"));
                    advance(Phase.SHAFT_SELECTION);
                }
                case SHAFT_SELECTION -> {
                    if (age() < 35) return;
                    checkSelection(runtime, "createShaft");
                    image("09-create-instanced-shaft-selected.png", () -> selectMechanism("motor"));
                    advance(Phase.MOTOR_SELECTION);
                }
                case MOTOR_SELECTION -> {
                    if (age() < 35) return;
                    checkSelection(runtime, "createMotor");
                    image("10-create-instanced-motor-selected.png", () -> selectMechanism("press"));
                    advance(Phase.PRESS_SELECTION);
                }
                case PRESS_SELECTION -> {
                    if (age() < 35) return;
                    checkSelection(runtime, "createPress");
                    image("11-create-instanced-press-selected.png", () -> enableHeatmap(mc, runtime));
                    advance(Phase.HEATMAP);
                }
                case HEATMAP -> {
                    if (age() < 35) return;
                    require(runtime.overlay().renderedWorldBoxes() >= 8 && runtime.overlay().renderedWorldLabels() > 0, "Combined world heatmap did not include instanced mechanisms");
                    JsonObject included = new JsonObject();
                    for (var mechanism : MECHANISMS.entrySet()) {
                        InstanceKey key = fixtureInstanceAt(runtime.profiler().snapshot(), "create", mechanism.getValue()).context().key();
                        require(runtime.overlay().renderedWorldBox(key), "Heatmap omitted instanced Create " + mechanism.getKey());
                        included.addProperty(mechanism.getKey(), true);
                    }
                    RESULT.add("instancedMechanismsInHeatmap", included);
                    RESULT.addProperty("combinedHeatmapBoxes", runtime.overlay().renderedWorldBoxes());
                    image("06-create-mekanism-heatmap.png", () -> { runtime.open(null); clickTab("capture"); });
                    advance(Phase.CAPTURE_SCREEN);
                }
                case CAPTURE_SCREEN -> {
                    if (age() < 20) return;
                    image("07-compatibility-capture.png", () -> {
                        selectMechanism("shaft");
                        MinecraftServer server = mc.getSingleplayerServer();
                        server.execute(() -> { command(server, "setblock -7 64 -3 minecraft:air"); shaftRemoved = true; });
                    });
                    advance(Phase.REMOVE_SHAFT);
                }
                case REMOVE_SHAFT -> {
                    if (!shaftRemoved || age() < 100) return;
                    require(mc.level.getBlockEntity(SHAFT) == null, "Removed shaft still exists on client");
                    require(runtime.profiler().snapshot().instances().stream().noneMatch(value -> value.context().key().equals(selected)), "Deleted Flywheel visual retained stale instance measurements");
                    require(!runtime.overlay().renderedWorldBox(selected) && !runtime.overlay().renderedWorldLabel(selected), "Deleted shaft retained a stale world overlay");
                    RESULT.addProperty("removedVisualExpiredAndOverlayCleared", true);
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> { command(server, "setblock -7 64 -3 create:shaft[axis=x]"); shaftRemoved = false; shaftRestored = true; });
                    advance(Phase.RESTORE_SHAFT);
                }
                case RESTORE_SHAFT -> {
                    if (!shaftRestored || age() < 80) return;
                    InstanceMetric shaft = fixtureInstanceAt(runtime.profiler().snapshot(), "create", SHAFT);
                    require(shaft.stats().calls() > 0 && shaft.stats().averageMs() > 0, "Recreated shaft has no new real visual measurements");
                    require(runtime.overlay().renderedWorldBox(shaft.context().key()), "Recreated selected shaft did not regain its world box");
                    RESULT.addProperty("recreatedVisualMeasured", true);
                    sceneReady = false;
                    advance(Phase.SESSION_CLEANUP);
                    // Match vanilla PauseScreen: close the connection before clearLevel waits
                    // for the integrated server's shutdown; clearLevel alone never requests it.
                    mc.level.disconnect();
                    mc.clearLevel(new TitleScreen());
                }
                case SESSION_CLEANUP -> {
                    if (age() < 12) return;
                    require(mc.level == null && runtime.profiler().snapshot().instances().isEmpty(), "World session retained profiler instances after disconnect");
                    require(!runtime.isHighlighted(selected) && !runtime.isTracked(selected), "World session retained selection after disconnect");
                    require(runtime.overlay().renderedWorldBoxes() == 0 && runtime.overlay().renderedWorldLabels() == 0, "Disconnected world retained overlay submissions");
                    require(FlywheelHooks.trackedVisuals() == 0, "Disconnected world retained Flywheel visual associations");
                    RESULT.addProperty("worldSessionMeasurementsAndSelectionCleared", true);
                    advance(Phase.FINISH);
                }
                case FINISH -> {
                    if (age() < 25 || !IMAGE_QUEUE.isEmpty() || IMAGE_WRITES.get() != 0) return;
                    require(IMAGES.size() == 11, "Missing compatibility screenshots");
                    RESULT.add("screenshots", IMAGES); RESULT.addProperty("success", true);
                    RESULT.addProperty("completedAt", Instant.now().toString()); writeResult();
                    LOGGER.info("RENDERSCOPE_COMPAT_SUCCESS: Create and Mekanism real timing, geometry, Flywheel default/fallback, selected instances and private capture verified");
                    finished = true; mc.stop();
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private static void constructScene(MinecraftServer server) throws Exception {
        require(server.getWorldData().getLevelName().equals(WORLD), "Refusing to modify an unrelated world");
        for (String block : List.of("create:creative_motor", "create:shaft", "create:cogwheel", "create:mechanical_press", "create:fluid_tank", "mekanism:basic_fluid_tank", "mekanism:advanced_fluid_tank", "mekanism:basic_energy_cube")) {
            require(BuiltInRegistries.BLOCK.containsKey(new ResourceLocation(block)), "Required fixture block is absent: " + block);
        }
        for (String text : List.of("gamerule doMobSpawning false", "gamerule doMobLoot false", "gamerule doEntityDrops false", "gamerule doTileDrops false",
                "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule sendCommandFeedback false", "time set day", "weather clear",
                "kill @e[type=!minecraft:player]", "fill -16 63 -12 16 63 18 minecraft:smooth_stone", "fill -16 64 -12 16 78 18 minecraft:air",
                "setblock -8 64 -3 create:creative_motor[facing=east]", "setblock -7 64 -3 create:shaft[axis=x]", "setblock -6 64 -3 create:shaft[axis=x]",
                "setblock -5 64 -3 create:cogwheel[axis=x]", "setblock -4 64 1 create:fluid_tank", "setblock -2 64 1 create:fluid_tank",
                "setblock -8 66 4 create:creative_motor[facing=east]", "setblock -7 66 4 create:mechanical_press[facing=east]",
                "setblock 4 64 1 mekanism:basic_fluid_tank", "setblock 6 64 1 mekanism:advanced_fluid_tank",
                "setblock 4 64 -3 mekanism:basic_energy_cube",
                "gamemode creative @a", "tp @a 0.5 64 15 180 9", "effect give @a minecraft:night_vision infinite 0 true")) command(server, text);
        ServerLevel level = server.overworld();
        setMotorSpeed(level, MOTOR, 32);
        setMotorSpeed(level, PRESS_MOTOR, 32);
        for (String mod : MODS) {
            BlockEntity tank = level.getBlockEntity(TARGETS.get(mod));
            require(tank != null, "No real tank block entity for " + mod);
            FIXTURE_TYPES.put(mod, BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tank.getType()).toString());
        }
        for (var mechanism : MECHANISMS.entrySet()) {
            BlockEntity tile = level.getBlockEntity(mechanism.getValue());
            require(tile != null, "Create " + mechanism.getKey() + " was not placed");
            FIXTURE_TYPES.put(mechanism.getKey(), BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType()).toString());
        }
        // Create calculates tank-controller connectivity during its first native server tick.
        // The normal animation task fills capabilities after that initialization has completed.
    }

    private static void animateScene(MinecraftServer server, boolean high) throws Exception {
        ServerLevel level = server.overworld();
        setMotorSpeed(level, MOTOR, high ? 64 : 32);
        BlockEntity press = level.getBlockEntity(PRESS);
        require(press != null, "Create press fixture disappeared");
        Object behaviour = press.getClass().getMethod("getPressingBehaviour").invoke(press);
        if (!behaviour.getClass().getField("running").getBoolean(behaviour)) {
            Class<?> mode = Class.forName("com.simibubi.create.content.kinetics.press.PressingBehaviour$Mode");
            behaviour.getClass().getMethod("start", mode).invoke(behaviour, mode.getField("WORLD").get(null));
        }
        for (BlockPos pos : TANKS) {
            BlockEntity tank = level.getBlockEntity(pos);
            require(tank != null, "Fixture tank disappeared");
            IFluidHandler handler = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP).resolve()
                    .orElseThrow(() -> new IllegalStateException("Fixture has no top fluid input capability"));
            IFluidHandler outputHandler = tank.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.DOWN).resolve().orElse(handler);
            int capacity = handler.getTanks() == 0 ? 0 : handler.getTankCapacity(0);
            if (capacity <= 0) {
                LOGGER.info("Waiting for native tank initialization at {}: {}", pos,
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
                return;
            }
            outputHandler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
            int requested = (int) (capacity * (high ? 0.7 : 0.35));
            int accepted = handler.fill(new FluidStack(Fluids.WATER, requested), IFluidHandler.FluidAction.EXECUTE);
            if (accepted <= 0 && BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace().equals("mekanism")) {
                // A tank's configured side modes can reject automation. Its public native tank API
                // still provides an exact, real world fixture and invokes normal contents listeners.
                Object inventory = tank.getClass().getField("fluidTank").get(tank);
                inventory.getClass().getMethod("setStack", FluidStack.class).invoke(inventory, new FluidStack(Fluids.WATER, requested));
                int stored = ((Number) inventory.getClass().getMethod("getFluidAmount").invoke(inventory)).intValue();
                require(stored == requested, "Native Mekanism tank did not retain fixture water");
                accepted = stored;
            }
            require(accepted > 0, "Fixture did not accept real water: " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock())
                    + " at " + pos + ", capacity=" + capacity + ", requested=" + requested);
            tank.setChanged(); level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            for (String method : List.of("sendDataImmediately", "sendUpdatePacket")) {
                try { tank.getClass().getMethod(method).invoke(tank); break; }
                catch (NoSuchMethodException ignored) { }
            }
        }
        BlockEntity shaft = level.getBlockEntity(SHAFT);
        serverShaftRpm = shaft == null ? 0 : ((Number) shaft.getClass().getMethod("getSpeed").invoke(shaft)).floatValue();
        BlockEntity cube = level.getBlockEntity(ENERGY_CUBE);
        require(cube != null, "Mekanism energy cube fixture disappeared");
        Object energyContainer = cube.getClass().getMethod("getEnergyContainer").invoke(cube);
        Object maximum = energyContainer.getClass().getMethod("getMaxEnergy").invoke(energyContainer);
        Object charge = maximum.getClass().getMethod("multiply", double.class).invoke(maximum, high ? 0.8 : 0.4);
        energyContainer.getClass().getMethod("setEnergy", Class.forName("mekanism.api.math.FloatingLong")).invoke(energyContainer, charge);
        cube.getClass().getMethod("sendUpdatePacket").invoke(cube);
        animationUpdates++;
    }

    private static void setMotorSpeed(ServerLevel level, BlockPos pos, int rpm) throws Exception {
        BlockEntity motor = level.getBlockEntity(pos);
        require(motor != null, "Create motor was not placed at " + pos);
        Object generatedSpeed = motor.getClass().getField("generatedSpeed").get(motor);
        generatedSpeed.getClass().getMethod("setValue", int.class).invoke(generatedSpeed, rpm);
        motor.getClass().getMethod("updateGeneratedRotation").invoke(motor);
        motor.getClass().getMethod("sendData").invoke(motor);
    }

    private static void observeClientMotion(Minecraft mc) throws Exception {
        float rpm = clientShaftSpeed(mc);
        if (rpm != 0 && lastClientShaftSpeed != 0 && rpm != lastClientShaftSpeed) shaftSpeedChanges++;
        lastClientShaftSpeed = rpm;
        BlockEntity press = mc.level.getBlockEntity(PRESS);
        if (press == null) return;
        Object behaviour = press.getClass().getMethod("getPressingBehaviour").invoke(press);
        float offset = ((Number) behaviour.getClass().getMethod("getRenderedHeadOffset", float.class).invoke(behaviour, mc.getFrameTime())).floatValue();
        if (Float.isFinite(lastClientPressOffset) && Math.abs(offset - lastClientPressOffset) > 0.0001F) pressHeadOffsetChanges++;
        lastClientPressOffset = offset;
    }

    private static float clientShaftSpeed(Minecraft mc) throws Exception {
        BlockEntity shaft = mc.level.getBlockEntity(SHAFT);
        return shaft == null ? 0 : ((Number) shaft.getClass().getMethod("getSpeed").invoke(shaft)).floatValue();
    }

    private static boolean ready(ProfilerSnapshot snapshot, boolean full) {
        for (String mod : MODS) {
            AggregateMetric value = snapshot.mods().stream().filter(row -> row.id().equals(mod)).findFirst().orElse(null);
            if (value == null || value.stats().calls() == 0 || value.stats().averageMs() <= 0 || full && value.stats().verticesPerFrame() <= 0) return false;
        }
        return true;
    }

    private static JsonObject checkMeasurements(ProfilerSnapshot snapshot, boolean full) {
        JsonObject result = new JsonObject();
        for (String mod : MODS) {
            AggregateMetric aggregate = snapshot.mods().stream().filter(row -> row.id().equals(mod)).findFirst().orElseThrow(() -> new IllegalStateException("No mod aggregate for " + mod));
            AggregateMetric type = snapshot.objects().stream().filter(row -> row.id().equals(FIXTURE_TYPES.get(mod))).findFirst().orElseThrow(() -> new IllegalStateException("No fixture object type for " + mod));
            InstanceMetric instance = fixtureInstance(snapshot, mod);
            for (Statistics stats : List.of(aggregate.stats(), type.stats(), instance.stats())) {
                require(stats.calls() > 0 && stats.averageMs() > 0 && stats.peakMs() > 0, "Nonpositive actual renderer measurements for " + mod);
                require(stats.failures() == 0, "Renderer failures recorded for " + mod);
                if (full) require(stats.verticesPerFrame() > 0 && !stats.renderTypes().isEmpty(), "Filled-tank Full metrics absent for " + mod);
                else require(stats.verticesPerFrame() == 0 && stats.estimatedBytesPerFrame() == 0 && stats.renderTypes().isEmpty(), "Timing Only retained geometry for " + mod);
            }
            require(instance.context().registryOwner().equals(mod), "Wrong registry attribution for " + mod);
            require(instance.context().rendererOwner().equals(mod), "Wrong renderer class attribution for " + mod + ": " + instance.context().rendererOwner());
            JsonObject row = new JsonObject();
            row.addProperty("type", instance.context().registryId()); row.addProperty("renderer", instance.context().rendererClass());
            row.addProperty("rendererOwner", instance.context().rendererOwner());
            row.add("modStatistics", GSON.toJsonTree(aggregate.stats())); row.add("typeStatistics", GSON.toJsonTree(type.stats()));
            row.add("instanceStatistics", GSON.toJsonTree(instance.stats())); result.add(mod, row);
        }
        return result;
    }

    private static JsonObject checkVisualMeasurements(ProfilerSnapshot snapshot, boolean full) throws Exception {
        require(flywheelOn(), "Instanced visual measurements must run with the real Flywheel backend enabled");
        JsonObject result = new JsonObject();
        for (var entry : MECHANISMS.entrySet()) {
            InstanceMetric instance = fixtureInstanceAt(snapshot, "create", entry.getValue());
            require(instance.context().registryId().equals(FIXTURE_TYPES.get(entry.getKey())), "Wrong registry type at instanced " + entry.getKey());
            require(instance.context().registryOwner().equals("create") && instance.context().rendererOwner().equals("create"),
                    "Wrong registry/visual implementation owner for " + entry.getKey());
            Statistics stats = instance.stats();
            MeasurementSource expectedSource = switch (entry.getKey()) {
                case "press" -> MeasurementSource.FLYWHEEL_VISUAL_FRAME;
                case "motor" -> MeasurementSource.FLYWHEEL_VISUAL_UPDATE;
                default -> MeasurementSource.FLYWHEEL_VISUAL_TICK;
            };
            var source = stats.sources().get(expectedSource);
            require(source != null && source.calls() > 0 && source.averageMs() > 0 && source.failures() == 0,
                    "No positive real visual " + expectedSource + " CPU timing for " + entry.getKey());
            String expectedVisual = switch (entry.getKey()) {
                case "press" -> "PressVisual";
                case "motor" -> "OrientedRotatingVisual";
                default -> "SingleAxisRotatingVisual";
            };
            AggregateMetric visual = snapshot.renderers().stream().filter(value -> value.rendererClass().contains(expectedVisual)
                    && value.rendererOwner().equals("create") && value.stats().sources().containsKey(expectedSource))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No actual visual implementation aggregate for " + entry.getKey()));
            require(stats.verticesPerFrame() == 0 && stats.estimatedBytesPerFrame() == 0,
                    "Instanced visual CPU callbacks must not invent emitted vertices or GPU transfer bytes for " + entry.getKey());
            if (!full) require(stats.renderTypes().isEmpty(), "Timing Only retained visual geometry metadata");
            JsonObject row = new JsonObject();
            row.addProperty("type", instance.context().registryId());
            row.addProperty("registryOwner", instance.context().registryOwner());
            row.addProperty("visualClass", visual.rendererClass());
            row.addProperty("visualOwner", visual.rendererOwner());
            row.addProperty("x", entry.getValue().getX()); row.addProperty("y", entry.getValue().getY()); row.addProperty("z", entry.getValue().getZ());
            row.addProperty("geometryCoverage", "Per-visual CPU callbacks; instanced GPU geometry is shared and not attributed per object");
            row.add("statistics", GSON.toJsonTree(stats));
            result.add(entry.getKey(), row);
        }
        return result;
    }

    private static void checkSharedFlywheelPhases(ProfilerSnapshot snapshot) {
        var phase = snapshot.phases().stream().filter(value -> value.stats().sources().containsKey(MeasurementSource.FLYWHEEL_BATCH_SUBMIT))
                .findFirst().orElseThrow(() -> new IllegalStateException("No shared Flywheel batch CPU phase"));
        var source = phase.stats().sources().get(MeasurementSource.FLYWHEEL_BATCH_SUBMIT);
        require(source.calls() > 0 && source.averageMs() > 0 && source.failures() == 0, "Shared Flywheel batch CPU phase has no real successful submissions");
        RESULT.add("flywheelSharedCpuPhase", GSON.toJsonTree(phase));
    }

    private static InstanceMetric fixtureInstance(ProfilerSnapshot snapshot, String mod) {
        return fixtureInstanceAt(snapshot, mod, TARGETS.get(mod));
    }

    private static InstanceMetric fixtureInstanceAt(ProfilerSnapshot snapshot, String mod, BlockPos pos) {
        return snapshot.instances().stream().filter(row -> row.context().key().kind() == ObjectKind.BLOCK_ENTITY
                && row.context().registryOwner().equals(mod) && row.context().position().x() == pos.getX()
                && row.context().position().y() == pos.getY() && row.context().position().z() == pos.getZ())
                .findFirst().orElseThrow(() -> new IllegalStateException("No actual selected fixture instance for " + mod));
    }

    private static void selectFixture(String mod) {
        selectInstance(mod, TARGETS.get(mod));
    }

    private static void selectMechanism(String mechanism) {
        selectInstance("create", MECHANISMS.get(mechanism));
    }

    private static void selectInstance(String mod, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance(); ClientRuntime runtime = ClientRuntime.current();
        runtime.overlay().clear(); runtime.settings().heatmap = false; runtime.settings().inspect = false; runtime.saveSettings();
        selected = fixtureInstanceAt(runtime.profiler().snapshot(), mod, pos).context().key();
        runtime.toggleHighlight(selected); runtime.toggleTrack(selected); mc.setScreen(null);
        MinecraftServer server = mc.getSingleplayerServer();
        int pitch = 12 - (pos.getY() - 64) * 10;
        server.execute(() -> command(server, String.format(Locale.ROOT, "tp @a %.1f 64 %.1f 180 %d", pos.getX() + 0.5, pos.getZ() + 6.5, pitch)));
    }

    private static void checkSelection(ClientRuntime runtime, String mod) {
        require(runtime.isHighlighted(selected) && runtime.isTracked(selected), "Selection state was lost for " + mod);
        require(runtime.overlay().renderedWorldBoxes() == 1 && runtime.overlay().renderedWorldLabels() == 1,
                "Single selected " + mod + " fixture did not submit exactly its own box and label");
        require(runtime.overlay().renderedWorldBox(selected) && runtime.overlay().renderedWorldLabel(selected), "Submitted overlay belongs to a different selected key");
        RESULT.addProperty(mod + "SelectedInstanceBoxAndLabel", true);
    }

    private static void enableHeatmap(Minecraft mc, ClientRuntime runtime) {
        runtime.overlay().clear(); runtime.settings().heatmap = true;
        runtime.settings().heatmapEntities = true; runtime.settings().heatmapBlockEntities = true;
        runtime.saveSettings(); mc.setScreen(null);
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> command(server, "tp @a 0.5 64 15 180 9"));
    }

    @SuppressWarnings("unchecked")
    private static void setFlywheelOff(Minecraft mc) throws Exception {
        Class<?> configClass = Class.forName("dev.engine_room.flywheel.impl.ForgeFlwConfig");
        Object config = configClass.getField("INSTANCE").get(null);
        Object client = configClass.getField("client").get(config);
        backendConfig = (ForgeConfigSpec.ConfigValue<String>) client.getClass().getField("backend").get(client);
        originalBackend = backendConfig.get(); backendConfig.set("flywheel:off");
        reloadBackend(mc);
    }

    private static void restoreFlywheel(Minecraft mc) throws Exception { backendConfig.set(originalBackend); reloadBackend(mc); }
    private static void reloadBackend(Minecraft mc) throws Exception {
        Class.forName("dev.engine_room.flywheel.impl.BackendManagerImpl")
                .getMethod("onReloadLevelRenderer", net.minecraft.client.multiplayer.ClientLevel.class).invoke(null, mc.level);
        mc.levelRenderer.allChanged();
    }
    private static boolean flywheelOn() throws Exception { return (boolean) Class.forName("dev.engine_room.flywheel.api.backend.BackendManager").getMethod("isBackendOn").invoke(null); }
    private static String backendName() throws Exception { return (String) Class.forName("dev.engine_room.flywheel.impl.BackendManagerImpl").getMethod("getBackendString").invoke(null); }

    private static void verifyExport(Minecraft mc, ClientRuntime runtime) throws IOException {
        String message = runtime.exportCapture(); require(message.startsWith("Saved:"), "Capture export failed: " + message);
        Path latest;
        try (var files = Files.list(mc.gameDirectory.toPath().resolve("renderscope-reports"))) {
            latest = files.filter(path -> path.getFileName().toString().endsWith(".json")).max(Comparator.comparing(path -> path.getFileName().toString())).orElseThrow();
        }
        JsonObject report = JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8)).getAsJsonObject();
        require(report.get("loader").getAsString().equals(loader.name()) && report.get("loaderVersion").getAsString().equals(loader.version()), "Export loader metadata mismatch");
        require(!report.get("positionsIncluded").getAsBoolean(), "Private coordinates unexpectedly enabled");
        privateKeys(report);
        for (String mod : MODS) require(report.getAsJsonArray("installedMods").asList().stream().anyMatch(row -> row.getAsJsonObject().get("id").getAsString().equals(mod)), "Export omitted installed mod " + mod);
        Files.copy(latest, output.resolve("compat-capture-example.json"), StandardCopyOption.REPLACE_EXISTING);
        RESULT.addProperty("captureExportMetadataAndPrivacy", true);
    }

    private static void privateKeys(JsonElement element) {
        Set<String> forbidden = Set.of("position", "dimension", "identity", "uuid", "playerusername", "username", "serverip", "serverhostname", "worldname", "chat", "absolutepath");
        if (element.isJsonObject()) for (var entry : element.getAsJsonObject().entrySet()) {
            require(!forbidden.contains(entry.getKey().toLowerCase(Locale.ROOT)), "Private report field " + entry.getKey()); privateKeys(entry.getValue());
        } else if (element.isJsonArray()) for (JsonElement row : element.getAsJsonArray()) privateKeys(row);
    }

    private static void openObject(String mod) {
        ClientRuntime.current().open(null); clickTab("objects");
        for (var child : Minecraft.getInstance().screen.children()) if (child instanceof EditBox search) { search.setValue(FIXTURE_TYPES.get(mod)); return; }
        throw new IllegalStateException("No native object search box");
    }

    private static void clickTab(String name) {
        require(Minecraft.getInstance().screen instanceof RenderScopeScreen, "Expected native RenderScope screen");
        String label = Component.translatable("renderscope.tab." + name).getString();
        for (var child : Minecraft.getInstance().screen.children()) if (child instanceof Button button && button.getMessage().getString().equals(label)) { button.onPress(); return; }
        throw new IllegalStateException("No native tab " + label);
    }

    public static void frame() {
        if (finished || IMAGE_QUEUE.isEmpty()) return;
        try {
            CaptureImage request = IMAGE_QUEUE.remove(); Minecraft mc = Minecraft.getInstance();
            IMAGE_WRITES.incrementAndGet();
            String file = "renderscope-compat-" + request.name();
            Screenshot.grab(mc.gameDirectory, file, mc.getMainRenderTarget(), message -> {
                try {
                    Path source = mc.gameDirectory.toPath().resolve("screenshots").resolve(file);
                    require(Files.isRegularFile(source), "Screenshot failed: " + message.getString());
                    Files.copy(source, output.resolve(request.name()), StandardCopyOption.REPLACE_EXISTING);
                    synchronized (IMAGES) { IMAGES.add(request.name()); }
                } catch (Throwable failure) { ERRORS.add(failure); }
                finally { IMAGE_WRITES.decrementAndGet(); }
            });
            if (request.after() != null) request.after().run();
        } catch (Throwable failure) { fail(failure); }
    }

    private static void image(String name, Runnable after) { IMAGE_QUEUE.add(new CaptureImage(name, after)); }
    private record CaptureImage(String name, Runnable after) {}
    private static int age() { return ticks - since; }
    private static void advance(Phase next) { phase = next; since = ticks; LOGGER.info("Compatibility phase: {}", next); }
    private static void command(MinecraftServer server, String text) { server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), text); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void writeResult() throws IOException { if (output != null) Files.writeString(output.resolve("compat-result.json"), GSON.toJson(RESULT), StandardCharsets.UTF_8); }
    private static void fail(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof InvocationTargetException
                || "Compatibility operation failed".equals(failure.getMessage()))) failure = failure.getCause();
        LOGGER.error("RENDERSCOPE_COMPAT_FAILURE during " + phase, failure);
        RESULT.addProperty("success", false); RESULT.addProperty("phase", phase.name()); RESULT.addProperty("error", failure.toString());
        RESULT.addProperty("completedAt", Instant.now().toString());
        try { writeResult(); } catch (IOException writeFailure) { LOGGER.error("Could not save compatibility result", writeFailure); }
        finished = true; Minecraft.getInstance().stop();
    }
}
