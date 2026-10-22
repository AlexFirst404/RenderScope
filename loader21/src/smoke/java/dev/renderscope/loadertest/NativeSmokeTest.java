package dev.renderscope.loadertest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.ClientRuntime;
import dev.renderscope.client.LoaderInfo;
import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.client.ui.RenderScopeScreen;
import dev.renderscope.core.CaptureReport;
import dev.renderscope.core.InstanceMetric;
import dev.renderscope.core.ObjectKind;
import dev.renderscope.core.ProfilerSnapshot;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Development-source mod: drives a real client, integrated server, real render hooks and native UI.
 * No generated timings or synthetic profiler calls are used. Never include this source set in a release jar.
 */
public final class NativeSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope/Smoke");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WORLD = "RenderScope-Smoke";
    private static final JsonObject RESULTS = new JsonObject();
    private static final JsonArray SCREENSHOTS = new JsonArray();
    private static final Queue<ScreenshotRequest> CAPTURE_QUEUE = new ArrayDeque<>();
    private static final Queue<Throwable> ASYNC_FAILURES = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger SCREENSHOTS_PENDING = new AtomicInteger();
    private static final long STARTED = System.nanoTime();
    private static volatile boolean sceneReady;
    private static int tick;
    private static int since;
    private static int stage;
    private static int animationFrame;
    private static boolean finished;
    private static Path output;
    private static Path gallery;
    private static LoaderInfo activeLoader;
    private static String stageName = "startup";

    public static void tick() {
        if (finished) return;
        tick++;
        try {
            if (output == null) {
                Path game = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
                String override = System.getProperty("renderscope.smoke.output");
                output = override == null ? game.getParent().resolve("distribution") : Path.of(override).toAbsolutePath();
                gallery = output.resolve("gallery");
                Files.createDirectories(gallery);
                RESULTS.addProperty("startedAt", Instant.now().toString());
                RESULTS.addProperty("source", "Real Minecraft client and integrated server; development-only harness");
            }
            Throwable async = ASYNC_FAILURES.poll();
            if (async != null) throw new IllegalStateException("Asynchronous smoke operation failed", async);
            require(System.nanoTime() - STARTED < 480_000_000_000L, "Smoke run timed out in " + stageName);
            Minecraft mc = Minecraft.getInstance();
            ClientRuntime runtime = ClientRuntime.current();
            if (runtime == null) return;
            switch (stage) {
                case 0 -> {
                    if (mc.getOverlay() != null || mc.screen == null) return;
                    activeLoader = LoaderInfo.current();
                    require(activeLoader.name().equals("Forge") || activeLoader.name().equals("NeoForge"),
                            "Unrecognized runtime loader: " + activeLoader);
                    String expectedLoader = System.getProperty("renderscope.expectedLoader", activeLoader.name());
                    String expectedVersion = System.getProperty("renderscope.expectedLoaderVersion", activeLoader.version());
                    require(activeLoader.name().equalsIgnoreCase(expectedLoader),
                            "Expected loader " + expectedLoader + " but running " + activeLoader.name());
                    require(activeLoader.version().equals(expectedVersion),
                            "Expected loader version " + expectedVersion + " but running " + activeLoader.version());
                    RESULTS.addProperty("loader", activeLoader.name());
                    RESULTS.addProperty("loaderVersion", activeLoader.version());
                    RESULTS.addProperty("runtimeLoaderVerified", true);
                    String actualMinecraft = net.minecraft.SharedConstants.getCurrentVersion().getName();
                    require(actualMinecraft.equals(System.getProperty("renderscope.expectedMinecraft", actualMinecraft)), "Wrong Minecraft runtime");
                    RESULTS.addProperty("minecraftVersion", actualMinecraft);
                    String environment = System.getProperty("renderscope.smoke.environment", "development");
                    RESULTS.addProperty("environment", environment);
                    Path actualPrimary = HarnessPlatform.primaryJar();
                    if (Files.isRegularFile(actualPrimary)) {
                        var digest = java.security.MessageDigest.getInstance("SHA-256");
                        try (var stream = Files.newInputStream(actualPrimary)) {
                            byte[] buffer = new byte[65536];
                            for (int count; (count = stream.read(buffer)) != -1;) digest.update(buffer, 0, count);
                        }
                        RESULTS.addProperty("primaryJarSha256", java.util.HexFormat.of().formatHex(digest.digest()));
                    } else require(environment.equals("development"), "Installed smoke did not load the main mod from a regular JAR");
                    RESULTS.addProperty("source", "Real Minecraft client and integrated server on "
                            + activeLoader.name() + " " + activeLoader.version() + "; development-only harness");
                    LOGGER.info("Verified actual runtime loader {} {}", activeLoader.name(), activeLoader.version());
                    mc.options.onboardAccessibility = false;
                    mc.options.tutorialStep = TutorialSteps.NONE;
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    mc.getToasts().clear();
                    mc.options.pauseOnLostFocus = false;
                    mc.options.renderDistance().set(5);
                    mc.options.framerateLimit().set(120);
                    mc.options.guiScale().set(2);
                    mc.options.bobView().set(false);
                    mc.options.gamma().set(1.0);
                    mc.options.hideGui = false;
                    mc.resizeDisplay();
                    runtime.settings().profiling = true;
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.FULL;
                    runtime.settings().sampleWindowSeconds = 2;
                    runtime.settings().includeWorldPositions = false;
                    runtime.settings().heatmap = false;
                    runtime.settings().inspect = false;
                    runtime.settings().throughWalls = false;
                    runtime.settings().heatmapEntities = true;
                    runtime.settings().heatmapBlockEntities = true;
                    runtime.settings().maximumDistance = 64;
                    runtime.settings().maxHighlightedObjects = 100;
                    runtime.settings().minimumCostMs = 0;
                    runtime.settings().showLabels = true;
                    runtime.settings().highlightTimeoutSeconds = 0;
                    runtime.saveSettings();
                    mc.setScreen(new TitleScreen());
                    if (mc.getLevelSource().levelExists(WORLD)) {
                        mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
                    } else {
                        LevelSettings levelSettings = new LevelSettings(WORLD, GameType.CREATIVE, false,
                                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(WORLD, levelSettings,
                                new WorldOptions(20261020L, false, false), access -> access.registryOrThrow(Registries.WORLD_PRESET)
                                        .getOrThrow(WorldPresets.FLAT).createWorldDimensions(), mc.screen);
                    }
                    advance("waiting for integrated server");
                }
                case 1 -> {
                    if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        try {
                            constructScene(server);
                            sceneReady = true;
                        } catch (Throwable failure) {
                            ASYNC_FAILURES.add(failure);
                        }
                    });
                    advance("warming real scene");
                }
                case 2 -> {
                    if (!sceneReady || mc.level == null || mc.player == null || tick - since < 120) return;
                    mc.setScreen(null);
                    ProfilerSnapshot snapshot = runtime.profiler().snapshot();
                    boolean entity = snapshot.instances().stream().anyMatch(value -> value.context().key().kind() == ObjectKind.ENTITY && value.stats().calls() > 0);
                    boolean blockEntity = snapshot.instances().stream().anyMatch(value -> value.context().key().kind() == ObjectKind.BLOCK_ENTITY && value.stats().calls() > 0);
                    boolean geometry = snapshot.performance().verticesPerFrame() > 0
                            && snapshot.instances().stream().anyMatch(value -> !value.stats().renderTypes().isEmpty());
                    if ((!entity || !blockEntity || !geometry) && tick - since < 500) return;
                    require(entity, "No real entity renderer samples");
                    require(blockEntity, "No real block-entity renderer samples");
                    require(geometry, "Full mode did not observe vertices and RenderType requests");
                    require(snapshot.performance().entityRenderMs() > 0 && snapshot.performance().blockEntityRenderMs() > 0,
                            "Measured renderer durations must be positive");
                    RESULTS.add("fullModePerformance", GSON.toJsonTree(snapshot.performance()));
                    RESULTS.addProperty("realEntityAndBlockEntitySamples", true);
                    RESULTS.addProperty("fullModeGeometryAndRenderTypes", true);
                    runtime.startCapture(5);
                    advance("five-second capture");
                }
                case 3 -> {
                    if (runtime.profiler().snapshot().capture().active() || tick - since < 110) return;
                    CaptureReport report = runtime.profiler().lastCapture();
                    require(report != null && report.frames() > 0, "Capture did not produce frames");
                    require(report.durationSeconds() >= 4.8, "Timed capture stopped too early");
                    RESULTS.addProperty("captureFrames", report.frames());
                    RESULTS.addProperty("captureDurationSeconds", report.durationSeconds());
                    String export = runtime.exportCapture();
                    require(export.startsWith("Saved:"), "JSON export failed: " + export);
                    Path reports = mc.gameDirectory.toPath().resolve("renderscope-reports");
                    Path latest;
                    try (var files = Files.list(reports)) {
                        latest = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                                .max(Comparator.comparing(path -> path.getFileName().toString())).orElseThrow();
                    }
                    JsonObject exported = JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8)).getAsJsonObject();
                    require(exported.get("loader").getAsString().equals(activeLoader.name()),
                            "Export reports the wrong loader: " + exported.get("loader"));
                    require(exported.get("loaderVersion").getAsString().equals(activeLoader.version()),
                            "Export reports the wrong loader version: " + exported.get("loaderVersion"));
                    RESULTS.addProperty("reportLoaderMetadataVerified", true);
                    require(!exported.get("positionsIncluded").getAsBoolean(), "Export positions unexpectedly enabled");
                    checkPrivateKeys(exported);
                    Files.copy(latest, output.resolve("smoke-capture-example.json"), StandardCopyOption.REPLACE_EXISTING);
                    RESULTS.addProperty("captureExportPrivacy", true);
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.TIMING_ONLY;
                    runtime.saveSettings();
                    advance("timing-only fallback");
                }
                case 4 -> {
                    if (tick - since < 85) return;
                    ProfilerSnapshot snapshot = runtime.profiler().snapshot();
                    require(snapshot.performance().measuredRenderMs() > 0 && snapshot.performance().rendererCallsPerFrame() > 0,
                            "Timing Only stopped renderer measurements");
                    require(snapshot.performance().verticesPerFrame() == 0 && snapshot.performance().estimatedBytesPerFrame() == 0,
                            "Timing Only retained geometry in the current rolling window");
                    RESULTS.addProperty("timingOnlyFallback", true);
                    runtime.settings().mode = ProfilerSettings.MeasurementMode.FULL;
                    runtime.saveSettings();
                    advance("restore full measurements");
                }
                case 5 -> {
                    if (tick - since < 75) return;
                    boolean registered = Arrays.stream(mc.options.keyMappings).anyMatch(mapping -> mapping.getName().equals("key.renderscope.open")
                            && mapping.getCategory().equals("key.categories.renderscope") && mapping.matches(GLFW.GLFW_KEY_F8, 0));
                    require(registered, "F8 key mapping is absent from Minecraft options");
                    mc.setScreen(null);
                    KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F8));
                    advance("F8 native menu");
                }
                case 6 -> {
                    if (tick - since < 8) return;
                    require(mc.screen instanceof RenderScopeScreen, "F8 did not open RenderScopeScreen");
                    RESULTS.addProperty("f8AndControlsRegistration", true);
                    screenshot("01-overview.png");
                    advance("overview screenshot");
                }
                case 7 -> {
                    if (tick - since < 16) return;
                    clickTab("mods");
                    advance("mods tab");
                }
                case 8 -> {
                    if (tick - since < 16) return;
                    screenshot("02-mods.png", () -> clickTab("objects"));
                    advance("objects tab");
                }
                case 9 -> {
                    if (tick - since < 16) return;
                    screenshot("03-objects.png");
                    advance("objects screenshot");
                }
                case 10 -> {
                    if (tick - since < 10) return;
                    require(mc.screen.mouseClicked(30, 119, 0), "First measured object row did not open");
                    advance("object details");
                }
                case 11 -> {
                    if (tick - since < 16) return;
                    screenshot("04-object-detail.png");
                    advance("object detail screenshot");
                }
                case 12 -> {
                    if (tick - since < 10) return;
                    clickTab("timeline");
                    advance("timeline tab");
                }
                case 13 -> {
                    if (tick - since < 16) return;
                    mc.screen.mouseClicked(mc.screen.width - 28, 110, 0);
                    advance("timeline real frame selection");
                }
                case 14 -> {
                    if (tick - since < 12) return;
                    screenshot("05-timeline.png");
                    advance("timeline screenshot");
                }
                case 15 -> {
                    if (tick - since < 10) return;
                    clickTab("capture");
                    advance("capture tab");
                }
                case 16 -> {
                    if (tick - since < 16) return;
                    screenshot("06-capture.png");
                    advance("capture screenshot");
                }
                case 17 -> {
                    if (tick - since < 10) return;
                    clickTab("heatmap");
                    advance("heatmap controls");
                }
                case 18 -> {
                    if (tick - since < 16) return;
                    screenshot("07-heatmap-controls.png");
                    advance("heatmap controls screenshot");
                }
                case 19 -> {
                    if (tick - since < 10) return;
                    mc.setScreen(new PauseScreen(true));
                    require(findButton("RenderScope") != null, "Pause screen has no injected RenderScope button");
                    advance("pause menu integration");
                }
                case 20 -> {
                    if (tick - since < 12) return;
                    screenshot("08-pause-menu.png");
                    advance("pause menu screenshot");
                }
                case 21 -> {
                    if (tick - since < 10) return;
                    findButton("RenderScope").onPress();
                    require(mc.screen instanceof RenderScopeScreen, "Pause menu button did not open RenderScope");
                    RESULTS.addProperty("pauseMenuButton", true);
                    mc.setScreen(null);
                    runtime.settings().heatmap = true;
                    runtime.settings().throughWalls = false;
                    runtime.settings().inspect = true;
                    runtime.saveSettings();
                    InstanceMetric target = runtime.profiler().snapshot().instances().stream()
                            .filter(value -> value.context().registryId().equals("minecraft:chest")).findFirst().orElseThrow();
                    runtime.toggleHighlight(target.context().key());
                    runtime.toggleTrack(target.context().key());
                    require(runtime.isHighlighted(target.context().key()) && runtime.isTracked(target.context().key()), "Highlight/Track did not toggle on");
                    RESULTS.addProperty("highlightAndTrackState", true);
                    advance("live world heatmap and inspector");
                }
                case 22 -> {
                    if (tick - since < 45) return;
                    require(runtime.overlay().renderedWorldBoxes() > 0, "Heatmap passed no real objects to world rendering");
                    require(runtime.overlay().renderedWorldLabels() > 0, "Projected world labels were all rejected");
                    RESULTS.addProperty("worldOverlayBoxesSubmitted", runtime.overlay().renderedWorldBoxes());
                    RESULTS.addProperty("worldOverlayLabelsSubmitted", runtime.overlay().renderedWorldLabels());
                    screenshot("09-world-heatmap.png");
                    advance("world heatmap screenshot");
                }
                case 23 -> {
                    if (tick - since < 10) return;
                    // Aim the actual player at the centre chest so the normal Minecraft hit result drives Inspector.
                    mc.getSingleplayerServer().execute(() -> command(mc.getSingleplayerServer(), "tp @a 0.5 64 4.4 180 16"));
                    advance("crosshair inspector");
                }
                case 24 -> {
                    if (tick - since < 35) return;
                    require(mc.hitResult instanceof BlockHitResult hit && mc.level.getBlockEntity(hit.getBlockPos()) != null,
                            "The real crosshair is not pointing at a block entity for Inspector");
                    RESULTS.addProperty("crosshairTargetsRealBlockEntity", true);
                    screenshot("10-crosshair-inspector.png");
                    advance("live animation frames");
                }
                case 25 -> {
                    if (tick - since < 5) return;
                    if (animationFrame < 16) {
                        if (animationFrame == 8) {
                            runtime.settings().throughWalls = true;
                            runtime.saveSettings();
                        }
                        screenshot(String.format(Locale.ROOT, "heatmap-frame-%02d.png", animationFrame++));
                        since = tick;
                    } else {
                        advance("waiting for screenshot writes");
                    }
                }
                case 26 -> {
                    if (tick - since < 30 || SCREENSHOTS_PENDING.get() != 0 || !CAPTURE_QUEUE.isEmpty()) return;
                    require(SCREENSHOTS.size() >= 26, "Expected gallery screenshots were not written");
                    runtime.open(null);
                    mc.options.guiScale().set(3);
                    mc.getWindow().setWindowed(960, 720);
                    mc.resizeDisplay();
                    advance("small 320 by 240 GUI layout");
                }
                case 27 -> {
                    if (tick - since < 20) return;
                    require(mc.screen instanceof RenderScopeScreen && mc.screen.width == 320 && mc.screen.height == 240,
                            "Small GUI size was not applied: " + mc.getWindow().getGuiScaledWidth() + "x" + mc.getWindow().getGuiScaledHeight());
                    screenshot("11-small-gui-320x240.png");
                    RESULTS.addProperty("smallGuiLayout", "320x240");
                    advance("small GUI screenshot");
                }
                case 28 -> {
                    if (tick - since < 12) return;
                    mc.options.guiScale().set(2);
                    mc.getWindow().setWindowed(1680, 720);
                    mc.resizeDisplay();
                    advance("ultrawide GUI layout");
                }
                case 29 -> {
                    if (tick - since < 20) return;
                    require(mc.screen instanceof RenderScopeScreen && mc.screen.width >= 800,
                            "Ultrawide window size was not applied");
                    screenshot("12-ultrawide-1680x720.png");
                    RESULTS.addProperty("ultrawideGuiLayout", mc.screen.width + "x" + mc.screen.height);
                    advance("final screenshot writes");
                }
                case 30 -> {
                    if (tick - since < 20 || SCREENSHOTS_PENDING.get() != 0 || !CAPTURE_QUEUE.isEmpty()) return;
                    require(SCREENSHOTS.size() >= 28, "Responsive-layout screenshots were not written");
                    RESULTS.add("screenshots", SCREENSHOTS);
                    RESULTS.addProperty("success", true);
                    RESULTS.addProperty("completedAt", Instant.now().toString());
                    writeResults();
                    finished = true;
                    LOGGER.info("RENDERSCOPE_SMOKE_SUCCESS: real renderer metrics, geometry, capture, privacy, F8, pause UI and {} screenshots verified", SCREENSHOTS.size());
                    mc.stop();
                }
                default -> throw new IllegalStateException("Unknown smoke stage " + stage);
            }
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** Called only after the real client has completed rendering a frame. */
    public static void frame() {
        if (finished || CAPTURE_QUEUE.isEmpty()) return;
        try {
            captureFrame();
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private static void captureFrame() {
        ScreenshotRequest request = CAPTURE_QUEUE.remove();
        String filename = request.filename();
        Minecraft mc = Minecraft.getInstance();
        SCREENSHOTS_PENDING.incrementAndGet();
        Screenshot.grab(mc.gameDirectory, "renderscope-smoke-" + filename, mc.getMainRenderTarget(), message -> {
            try {
                Path source = mc.gameDirectory.toPath().resolve("screenshots").resolve("renderscope-smoke-" + filename);
                require(Files.isRegularFile(source), "Screenshot failed: " + message.getString());
                Files.copy(source, gallery.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                synchronized (SCREENSHOTS) {
                    SCREENSHOTS.add(filename);
                }
                LOGGER.info("Saved real Minecraft screenshot {}", filename);
            } catch (Throwable failure) {
                ASYNC_FAILURES.add(failure);
            } finally {
                SCREENSHOTS_PENDING.decrementAndGet();
            }
        });
        if (request.afterCapture() != null) request.afterCapture().run();
    }

    private static void constructScene(MinecraftServer server) {
        require(server.getWorldData().getLevelName().equals(WORLD), "Refusing to modify an unexpected world");
        List<String> commands = List.of(
                "gamerule doMobSpawning false", "gamerule doMobLoot false", "gamerule doEntityDrops false",
                "gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                "gamerule sendCommandFeedback false", "time set day", "weather clear",
                "kill @e[type=!minecraft:player]", "fill -12 63 -12 12 63 12 minecraft:smooth_stone",
                "fill -12 64 -12 12 78 12 minecraft:air",
                "setblock -4 64 0 minecraft:chest[facing=south]", "setblock -2 64 0 minecraft:chest[facing=south]",
                "setblock 0 64 0 minecraft:chest[facing=south]", "setblock 2 64 0 minecraft:chest[facing=south]",
                "setblock 4 64 0 minecraft:chest[facing=south]", "setblock -3 64 -3 minecraft:enchanting_table",
                "setblock 0 64 -3 minecraft:enchanting_table", "setblock 3 64 -3 minecraft:enchanting_table",
                "fill -7 63 -5 -5 63 -3 minecraft:iron_block", "setblock -6 64 -4 minecraft:beacon",
                "setblock 6 64 -3 minecraft:bell[attachment=floor,facing=south]",
                "summon minecraft:cow -4 64 3 {NoAI:1b,Silent:1b,PersistenceRequired:1b}",
                "summon minecraft:sheep -1.8 64 3 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Color:14b}",
                "summon minecraft:villager 2 64 3 {NoAI:1b,Silent:1b,PersistenceRequired:1b}",
                "summon minecraft:armor_stand 4 64 3 {NoGravity:1b,ShowArms:1b,ArmorItems:[{id:\"minecraft:iron_boots\",Count:1b},{id:\"minecraft:iron_leggings\",Count:1b},{id:\"minecraft:iron_chestplate\",Count:1b},{id:\"minecraft:iron_helmet\",Count:1b}]}",
                "gamemode creative @a", "tp @a 0.5 64 11.5 180 8", "effect give @a minecraft:night_vision infinite 0 true"
        );
        for (String text : commands) command(server, text);
        LOGGER.info("Known smoke world populated with actual chests, beacon, enchanting tables, bell, cow, sheep, villager and armor stand");
    }

    private static void command(MinecraftServer server, String text) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), text);
    }

    private static void checkPrivateKeys(JsonElement element) {
        Set<String> forbidden = Set.of("position", "dimension", "identity", "uuid", "playerusername", "username",
                "serverip", "serverhostname", "worldname", "chat", "absolutepath");
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                require(!forbidden.contains(entry.getKey().toLowerCase(Locale.ROOT)), "Private JSON field present: " + entry.getKey());
                checkPrivateKeys(entry.getValue());
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) checkPrivateKeys(child);
        }
    }

    private static void clickTab(String tab) {
        require(Minecraft.getInstance().screen instanceof RenderScopeScreen, "RenderScope screen closed unexpectedly");
        String label = Component.translatable("renderscope.tab." + tab).getString();
        Button button = findButton(label);
        require(button != null, "Missing native tab button: " + label);
        button.onPress();
    }

    private static Button findButton(String label) {
        var screen = Minecraft.getInstance().screen;
        if (screen == null) return null;
        for (var child : screen.children()) if (child instanceof Button button && button.getMessage().getString().equals(label)) return button;
        return null;
    }

    private static void screenshot(String filename) {
        screenshot(filename, null);
    }

    private static void screenshot(String filename, Runnable afterCapture) {
        CAPTURE_QUEUE.add(new ScreenshotRequest(filename, afterCapture));
    }

    private record ScreenshotRequest(String filename, Runnable afterCapture) {}

    private static void advance(String next) {
        stage++;
        since = tick;
        stageName = next;
        LOGGER.info("Smoke stage {}: {}", stage, next);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void fail(Throwable failure) {
        finished = true;
        LOGGER.error("RENDERSCOPE_SMOKE_FAILURE in stage " + stage + ": " + stageName, failure);
        RESULTS.addProperty("success", false);
        RESULTS.addProperty("stage", stageName);
        RESULTS.addProperty("error", failure.toString());
        try {
            ClientRuntime runtime = ClientRuntime.current();
            if (runtime != null) RESULTS.add("failurePerformance", GSON.toJsonTree(runtime.profiler().snapshot().performance()));
            for (Class<?> type : new Class<?>[]{Minecraft.class, net.minecraft.client.renderer.entity.EntityRenderDispatcher.class}) {
                var injected = java.util.Arrays.stream(type.getDeclaredMethods()).map(java.lang.reflect.Method::getName)
                        .filter(name -> name.contains("renderscope")).toList();
                RESULTS.add(type.getSimpleName() + "MixinMethods", GSON.toJsonTree(injected));
            }
            RESULTS.add("loadedMixinConfigs", GSON.toJsonTree(org.spongepowered.asm.mixin.Mixins.getConfigs().stream()
                    .map(Object::toString).toList()));
        } catch (Throwable diagnosticFailure) {
            RESULTS.addProperty("diagnosticError", diagnosticFailure.toString());
        }
        RESULTS.addProperty("completedAt", Instant.now().toString());
        try {
            writeResults();
        } catch (IOException writeFailure) {
            LOGGER.error("Unable to write smoke failure result", writeFailure);
        }
        Minecraft.getInstance().stop();
    }

    private static void writeResults() throws IOException {
        if (output == null) return;
        Files.writeString(output.resolve("smoke-result.json"), GSON.toJson(RESULTS), StandardCharsets.UTF_8);
    }
}
