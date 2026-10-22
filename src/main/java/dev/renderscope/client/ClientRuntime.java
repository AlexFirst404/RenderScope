package dev.renderscope.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.report.ReportExporter;
import dev.renderscope.client.ui.RenderScopeScreen;
import dev.renderscope.client.ui.ScreenActions;
import dev.renderscope.client.world.WorldOverlay;
import dev.renderscope.client.platform.PlatformAccess;
import dev.renderscope.client.platform.PlatformServices;
import dev.renderscope.core.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class ClientRuntime implements ScreenActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope");
    private static ClientRuntime instance;
    private static final KeyMapping OPEN = new KeyMapping("key.renderscope.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.renderscope");
    private final RenderProfiler profiler = new RenderProfiler();
    private final ProfilerSettings settings = ProfilerSettings.load(PlatformServices.current().configDirectory().resolve("renderscope.json"));
    private final ModOwnership ownership = new ModOwnership();
    private final WorldOverlay overlay = new WorldOverlay(profiler, settings);
    private String captureMeasurementMode = ProfilerSettings.MeasurementMode.TIMING_ONLY.name();
    private boolean captureCpuTiming;

    private ClientRuntime() { }
    public static ClientRuntime current() { return instance; }
    public static ClientRuntime initialize(PlatformAccess platform) {
        PlatformServices.install(platform);
        instance = new ClientRuntime();
        instance.applySettings();
        LOGGER.info("RenderScope ready: F8 / Pause Menu. CPU timing; no server component.");
        return instance;
    }
    public static KeyMapping openKey() { return OPEN; }

    public RenderProfiler profiler() { return profiler; }
    public ModOwnership ownership() { return ownership; }
    public WorldOverlay overlay() { return overlay; }
    @Override public ProfilerSettings settings() { return settings; }

    public void clientTick() {
        while (OPEN.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RenderScopeScreen) mc.screen.onClose();
            else if (mc.level != null) open(mc.screen);
        }
    }

    public boolean screenKey(Screen screen, int keyCode, int scanCode) {
        if (Minecraft.getInstance().level == null || !OPEN.matches(keyCode, scanCode)) return false;
        if (screen instanceof KeyBindsScreen || screen.getFocused() instanceof EditBox) return false;
        while (OPEN.consumeClick()) { /* discard a queued gameplay key press */ }
        OPEN.setDown(false);
        if (screen instanceof RenderScopeScreen) screen.onClose();
        else open(screen);
        return true;
    }

    public void open(Screen parent) {
        Minecraft.getInstance().setScreen(new RenderScopeScreen(parent, profiler, this));
    }

    public void pauseMenu(Screen screen, List<?> listeners, Consumer<Button> addButton) {
        if (!(screen instanceof PauseScreen) || Minecraft.getInstance().level == null) return;
        // Find an unused 100x20 area; leave all vanilla and other mods' buttons intact.
        int x = 6, y = screen.height - 26;
        outer: for (int candidateY = screen.height - 26; candidateY >= 6; candidateY -= 24) {
            for (int candidateX : new int[]{6, Math.max(6, screen.width - 106)}) {
                boolean free = true;
                for (var child : listeners) {
                    if (child instanceof AbstractWidget w && w.visible && candidateX < w.getX()+w.getWidth()
                            && candidateX+100 > w.getX() && candidateY < w.getY()+w.getHeight()
                            && candidateY+20 > w.getY()) { free = false; break; }
                }
                if (free) { x=candidateX; y=candidateY; break outer; }
            }
        }
        addButton.accept(Button.builder(Component.literal("RenderScope"), button -> open(screen))
                .bounds(x,y,100,20).build());
    }

    public void beginWorldPass() {
        if (dev.renderscope.client.integration.ShaderPasses.isShadowPass()) return;
        RenderHooks.worldPass(true);
        FlywheelGpuTimers.frameStarted(settings.profiling
                && settings.mode == ProfilerSettings.MeasurementMode.FULL && hasInstancedRenderer());
    }
    public void endWorldPass() {
        if (!dev.renderscope.client.integration.ShaderPasses.isShadowPass()) RenderHooks.worldPass(false);
    }

    private void applySettings() {
        settings.sanitize();
        if (profiler.snapshot().capture().active()) {
            if (!captureMeasurementMode.equals(settings.mode.name())) captureMeasurementMode = "MIXED";
            captureCpuTiming |= settings.cpuTiming;
        }
        profiler.setEnabled(settings.profiling);
        profiler.setCpuTimingEnabled(settings.cpuTiming);
        profiler.setSampleWindowSeconds(settings.sampleWindowSeconds);
        RenderHooks.setGeometryEnabled(settings.mode == ProfilerSettings.MeasurementMode.FULL);
        if (!settings.profiling || settings.mode != ProfilerSettings.MeasurementMode.FULL)
            FlywheelGpuTimers.reset();
    }
    @Override public void saveSettings() { applySettings(); settings.save(); }
    @Override public String modDisplayName(String namespace) { return ModOwnership.displayName(namespace); }
    @Override public boolean hasInstancedRenderer() {
        return dev.renderscope.client.integration.IntegrationVersions.supportsFlywheel(
                dev.renderscope.client.integration.IntegrationVersions.installed());
    }
    @Override public FlywheelGpuTimers.Snapshot flywheelGpuTiming() { return FlywheelGpuTimers.snapshot(); }
    @Override public void toggleHighlight(InstanceKey key) { overlay.toggleHighlight(key); }
    @Override public void toggleTrack(InstanceKey key) { overlay.toggleTrack(key); }
    @Override public boolean isHighlighted(InstanceKey key) { return overlay.isHighlighted(key); }
    @Override public boolean isTracked(InstanceKey key) { return overlay.isTracked(key); }
    @Override public void startCapture(int seconds) {
        captureMeasurementMode = settings.mode.name();
        captureCpuTiming = settings.cpuTiming;
        profiler.startCapture(seconds);
    }
    @Override public void stopCapture() { profiler.stopCapture(); }
    @Override public String exportCapture() {
        CaptureReport capture = profiler.lastCapture();
        if (capture == null) return "Finish a capture first.";
        try {
            var file = ReportExporter.write(Minecraft.getInstance().gameDirectory.toPath(), capture,
                    settings.includeWorldPositions, captureMeasurementMode, captureCpuTiming);
            return "Saved: renderscope-reports/" + file.getFileName();
        } catch (IOException exception) {
            LOGGER.error("Could not write RenderScope report", exception);
            return "Export failed: " + exception.getClass().getSimpleName();
        }
    }
}
