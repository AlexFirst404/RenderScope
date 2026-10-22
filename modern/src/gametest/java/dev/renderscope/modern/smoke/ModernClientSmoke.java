package dev.renderscope.modern.smoke;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.core.*;
import dev.renderscope.modern.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.PauseScreen;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Actual game/world/UI test. It never submits synthetic measurements to RenderProfiler. */
@SuppressWarnings("UnstableApiUsage")
public final class ModernClientSmoke implements FabricClientGameTest {
    private final JsonObject results = new JsonObject();
    private final JsonArray screenshots = new JsonArray();
    private final Path output = Path.of(System.getProperty("renderscope.modern.smoke.output", "renderscope-modern-validation"));
    @Override public void runTest(ClientGameTestContext context) {
        results.addProperty("success", false);
        results.addProperty("startedAt", Instant.now().toString());
        results.addProperty("minecraftVersion", FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString());
        results.addProperty("loaderVersion", FabricLoader.getInstance().getModContainer("fabricloader").orElseThrow().getMetadata().getVersion().getFriendlyString());
        results.addProperty("geometryAvailable", false); results.addProperty("gpuTimingAvailable", false);
        results.addProperty("environment", "fabric-development-client");
        try {
            Files.createDirectories(output);
            Path artifact = Path.of(System.getProperty("renderscope.modern.smoke.artifact"));
            byte[] artifactBytes = Files.readAllBytes(artifact);
            results.addProperty("artifact", artifact.getFileName().toString());
            results.addProperty("artifactBytes", artifactBytes.length);
            results.addProperty("artifactSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(artifactBytes)));
            context.restoreDefaultGameOptions();
            context.getInput().resizeWindow(1280,720);
            try (TestSingleplayerContext world = context.worldBuilder().create()) {
                var server = world.getServer();
                for (String command : new String[]{
                    "fill -12 80 -12 12 80 15 minecraft:stone",
                    "setblock 0 81 0 minecraft:chest[facing=south]",
                    "setblock -3 81 0 minecraft:enchanting_table",
                    "fill 2 80 -1 4 80 1 minecraft:iron_block",
                    "setblock 3 81 0 minecraft:beacon",
                    "summon minecraft:cow 0.5 81 5.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b}",
                    "summon minecraft:sheep -3 81 5 {NoAI:1b,Silent:1b,PersistenceRequired:1b}",
                    "gamemode creative @a", "time set noon", "weather clear", "tp @a 0.5 81 12.5 180 8"}) server.runCommand(command);
                context.runOnClient(mc -> {
                    ModernHooks.SETTINGS.profiling=true; ModernHooks.SETTINGS.cpuTiming=true;
                    ModernHooks.SETTINGS.includeWorldPositions=false; ModernHooks.SETTINGS.minimumCostMs=0;
                    ModernHooks.SETTINGS.heatmap=false; ModernHooks.SETTINGS.inspect=false;
                    ModernHooks.applySettings();
                });
                context.waitFor(mc -> hasMeasured("minecraft:chest") && hasMeasured("minecraft:cow"), 2000);
                ProfilerSnapshot snapshot = context.computeOnClient(mc -> ModernHooks.PROFILER.snapshot());
                require(snapshot.instances().stream().allMatch(metric -> metric.stats().verticesPerFrame()==0),"Unavailable geometry must not become invented counts");
                results.addProperty("entityAndBlockEntityExtractionAndSubmission",true);
                results.addProperty("extractionScopes",ModernHooks.extractionCalls());
                results.addProperty("submissionScopes",ModernHooks.submissionCalls());
                screenshot(context,"world-baseline.png");

                context.getInput().pressKey(InputConstants.KEY_F8);
                context.waitForScreen(RenderScopeScreen.class);
                results.addProperty("f8NativeScreen",true);
                screenshot(context,"overview.png");
                context.clickScreenButton("Mods"); screenshot(context,"mods.png");
                context.clickScreenButton("Objects"); screenshot(context,"objects.png");
                clickFirstRow(context); // Actual object-type row drills down to its measured instances.
                String selectedRegistry = clickFirstRow(context);
                context.waitTicks(3);
                String ownerButton = context.computeOnClient(mc -> ModernSmokePlatform.currentScreen(mc).children().stream()
                    .filter(net.minecraft.client.gui.components.Button.class::isInstance)
                    .map(net.minecraft.client.gui.components.Button.class::cast)
                    .map(button -> button.getMessage().getString()).filter(message -> message.startsWith("Registry: ")).findFirst().orElseThrow());
                context.clickScreenButton(ownerButton);
                String copiedRenderer = context.computeOnClient(mc -> mc.keyboardHandler.getClipboard());
                require(context.computeOnClient(mc -> ModernHooks.PROFILER.snapshot().instances().stream().anyMatch(metric ->
                    metric.context().registryId().equals(selectedRegistry) && metric.context().rendererClass().equals(copiedRenderer))),
                    "Copied renderer implementation does not match the measured instance");
                double[] ownerCursor = context.computeOnClient(mc -> {
                    var owner = ModernSmokePlatform.currentScreen(mc).children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                        .map(net.minecraft.client.gui.components.Button.class::cast)
                        .filter(button -> button.getMessage().getString().startsWith("Registry: ")).findFirst().orElseThrow();
                    return new double[]{(owner.getX()+owner.getWidth()/2.0)*mc.getWindow().getScreenWidth()/ModernSmokePlatform.currentScreen(mc).width,
                        (owner.getY()+owner.getHeight()/2.0)*mc.getWindow().getScreenHeight()/ModernSmokePlatform.currentScreen(mc).height};
                });
                context.getInput().setCursorPos(ownerCursor[0],ownerCursor[1]);
                screenshot(context,"instance-details.png");
                results.addProperty("selectedInstanceOwnerDetails",true);
                results.addProperty("rendererClassCopy",true);
                results.addProperty("selectedRendererClass",copiedRenderer);
                context.clickScreenButton("Highlight");
                context.waitFor(mc -> ModernOverlay.queuedBoxes()==1,500);
                results.addProperty("highlightNativeButton",true);
                context.runOnClient(mc -> { ModernOverlay.clear(); mc.setScreenAndShow(new RenderScopeScreen(null)); });
                context.clickScreenButton("Timeline"); screenshot(context,"timeline.png");
                context.clickScreenButton("Settings"); screenshot(context,"settings.png");
                context.runOnClient(mc -> mc.setScreenAndShow(null));
                context.getInput().pressKey(InputConstants.KEY_ESCAPE);
                context.waitForScreen(PauseScreen.class);
                context.clickScreenButton("RenderScope");
                context.waitForScreen(RenderScopeScreen.class);
                results.addProperty("pauseNativeButton",true);
                context.runOnClient(mc -> mc.setScreenAndShow(null));

                server.runCommand("tp @a 2.5 81 3.5 180 8");
                context.waitTicks(4);
                lookAtChest(context);
                InstanceMetric chest=snapshot.instances().stream().filter(metric->metric.context().registryId().equals("minecraft:chest")).findFirst().orElseThrow();
                context.runOnClient(mc -> ModernOverlay.toggleHighlight(chest.context().key()));
                context.waitFor(mc -> ModernOverlay.queuedBoxes()==1 && ModernOverlay.queuedLabels()==2,500);
                screenshot(context,"selected-instance.png");
                results.addProperty("selectedGizmoBoxAndLabels",true);
                context.runOnClient(mc -> { ModernOverlay.clear(); ModernHooks.SETTINGS.heatmap=true; ModernHooks.applySettings(); });
                context.waitFor(mc -> ModernOverlay.queuedBoxes()>=3,500);
                screenshot(context,"heatmap.png");
                int heatmapBoxes=context.computeOnClient(mc -> ModernOverlay.queuedBoxes());
                results.addProperty("heatmapGizmoBoxes",heatmapBoxes);
                context.runOnClient(mc -> { ModernHooks.SETTINGS.heatmap=false; ModernHooks.SETTINGS.inspect=true; ModernHooks.applySettings(); });
                lookAtChest(context);
                context.waitFor(mc -> ModernOverlay.inspected().isPresent(),500);
                // The data predicate may already be true on the settings tick.
                // Wait for actual HUD extraction/rendering before framebuffer readback.
                context.waitTicks(2);
                screenshot(context,"inspector.png");
                results.addProperty("realCrosshairInspector",true);

                context.runOnClient(mc -> ModernHooks.PROFILER.startCapture(5));
                context.waitFor(mc -> ModernHooks.PROFILER.lastCapture()!=null,10000);
                CaptureReport capture=context.computeOnClient(mc -> ModernHooks.PROFILER.lastCapture());
                require(capture.durationSeconds()>=5 && capture.frames()>20,"Real capture is insufficient");
                require(capture.instances().stream().anyMatch(metric->metric.stats().sources().containsKey(MeasurementSource.RENDER_SUBMISSION)),"Missing captured submission source");
                Path report=context.computeOnClient(mc -> ModernReportExporter.write(capture,false));
                JsonObject exported=JsonParser.parseString(Files.readString(report)).getAsJsonObject();
                checkPrivacy(exported);
                Files.copy(report,output.resolve("capture-example.json"),StandardCopyOption.REPLACE_EXISTING);
                results.addProperty("captureSeconds",capture.durationSeconds()); results.addProperty("captureFrames",capture.frames());
                results.addProperty("reportPrivacy",true);
                context.runOnClient(mc -> mc.setScreenAndShow(new RenderScopeScreen(null)));
                context.clickScreenButton("Capture"); screenshot(context,"capture.png");
                context.getInput().resizeWindow(320,240); context.waitTicks(5); screenshot(context,"small-ui.png");
                context.getInput().resizeWindow(1600,720); context.waitTicks(5); screenshot(context,"wide-ui.png");
                context.runOnClient(mc -> mc.setScreenAndShow(null));
            }
            context.waitFor(mc -> mc.level==null,500);
            context.waitTicks(5);
            require(context.computeOnClient(mc -> ModernHooks.PROFILER.snapshot().instances().isEmpty()),"Disconnected world instances retained");
            results.addProperty("disconnectClearsInstances",true);
            results.addProperty("success",true);
        } catch (Throwable failure) {
            results.addProperty("error",failure.toString());
            throw failure instanceof RuntimeException runtime ? runtime : new RuntimeException(failure);
        } finally {
            results.add("screenshots",screenshots);
            results.addProperty("completedAt",Instant.now().toString());
            try { Files.createDirectories(output); Files.writeString(output.resolve("smoke-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(results),StandardCharsets.UTF_8); }
            catch (java.io.IOException failure) { throw new RuntimeException("Cannot save runtime evidence",failure); }
        }
    }
    private static boolean hasMeasured(String registryId) {
        return ModernHooks.PROFILER.snapshot().instances().stream().anyMatch(metric -> metric.context().registryId().equals(registryId)
            &&metric.stats().sources().containsKey(MeasurementSource.RENDER_EXTRACTION)
            &&metric.stats().sources().containsKey(MeasurementSource.RENDER_SUBMISSION)
            &&metric.stats().averageMs()>0);
    }
    private static String clickFirstRow(ClientGameTestContext context) {
        String registry = context.computeOnClient(mc -> {
            var list = ModernSmokePlatform.currentScreen(mc).children().stream().filter(net.minecraft.client.gui.components.ObjectSelectionList.class::isInstance)
                .map(net.minecraft.client.gui.components.ObjectSelectionList.class::cast).findFirst().orElseThrow();
            var first = (net.minecraft.client.gui.components.ObjectSelectionList.Entry<?>)list.children().getFirst();
            String id = first.getNarration().getString().split(" ",2)[0];
            require(list.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(list.getRowLeft()+4,list.getRowTop(0)+4,
                new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false),"Native first row did not accept selection");
            return id;
        });
        context.waitTicks(2);
        return registry;
    }
    private static void lookAtChest(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            var offset = new net.minecraft.world.phys.Vec3(0.5,81.5,0.5).subtract(mc.player.getEyePosition());
            mc.player.setYRot((float)Math.toDegrees(Math.atan2(-offset.x, offset.z)));
            mc.player.setXRot((float)-Math.toDegrees(Math.atan2(offset.y, Math.sqrt(offset.x*offset.x+offset.z*offset.z))));
        });
        context.waitTicks(3);
    }
    private void screenshot(ClientGameTestContext context,String filename) throws java.io.IOException {
        context.waitTicks(3);
        Path source=context.takeScreenshot("renderscope-"+filename.replace(".png",""));
        Files.copy(source,output.resolve(filename),StandardCopyOption.REPLACE_EXISTING);screenshots.add(filename);
    }
    private static void require(boolean condition,String message) { if(!condition)throw new IllegalStateException(message); }
    private static void checkPrivacy(JsonElement element) {
        Set<String> forbidden=Set.of("identity","uuid","position","dimension","username","playerusername","serverip","serverhostname","worldname","chat","absolutepath");
        if(element.isJsonObject())for(Map.Entry<String,JsonElement> entry:element.getAsJsonObject().entrySet()) {
            require(!forbidden.contains(entry.getKey().toLowerCase(Locale.ROOT)),"Private report field "+entry.getKey());checkPrivacy(entry.getValue());
        } else if(element.isJsonArray())for(JsonElement item:element.getAsJsonArray())checkPrivacy(item);
    }
}
