package dev.renderscope.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.client.report.ReportExporter;
import dev.renderscope.client.ui.RenderScopeScreen;
import dev.renderscope.client.ui.ScreenActions;
import dev.renderscope.client.world.WorldOverlay;
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
import net.minecraftforge.client.event.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;

public final class ClientRuntime implements ScreenActions {
    private static final Logger LOGGER = LoggerFactory.getLogger("RenderScope");
    private static ClientRuntime instance;
    private static final KeyMapping OPEN = new KeyMapping("key.renderscope.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8, "key.categories.renderscope");
    private final RenderProfiler profiler = new RenderProfiler();
    private final ProfilerSettings settings = ProfilerSettings.load(FMLPaths.CONFIGDIR.get().resolve("renderscope.json"));
    private final ModOwnership ownership = new ModOwnership();
    private final WorldOverlay overlay = new WorldOverlay(profiler, settings);
    private String captureMeasurementMode = ProfilerSettings.MeasurementMode.TIMING_ONLY.name();
    private boolean captureCpuTiming;

    private ClientRuntime() { }
    public static ClientRuntime current() { return instance; }
    public static void initialize() {
        instance = new ClientRuntime();
        instance.applySettings();
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener((RegisterKeyMappingsEvent event) -> event.register(OPEN));
        MinecraftForge.EVENT_BUS.addListener(instance::clientTick);
        MinecraftForge.EVENT_BUS.addListener(instance::pauseMenu);
        MinecraftForge.EVENT_BUS.addListener(instance::screenKey);
        MinecraftForge.EVENT_BUS.addListener(instance::renderWorld);
        MinecraftForge.EVENT_BUS.addListener(instance.overlay::renderHud);
        LOGGER.info("RenderScope ready: F8 / Pause Menu. CPU timing; no server component.");
    }

    public RenderProfiler profiler() { return profiler; }
    public ModOwnership ownership() { return ownership; }
    public WorldOverlay overlay() { return overlay; }
    @Override public ProfilerSettings settings() { return settings; }

    private void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        while (OPEN.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RenderScopeScreen) mc.screen.onClose();
            else if (mc.level != null) open(mc.screen);
        }
    }

    private void screenKey(ScreenEvent.KeyPressed.Pre event) {
        if (Minecraft.getInstance().level == null || !OPEN.matches(event.getKeyCode(), event.getScanCode())) return;
        if (event.getScreen() instanceof KeyBindsScreen || event.getScreen().getFocused() instanceof EditBox) return;
        while (OPEN.consumeClick()) { /* discard a queued gameplay key press */ }
        OPEN.setDown(false);
        if (event.getScreen() instanceof RenderScopeScreen) event.getScreen().onClose();
        else open(event.getScreen());
        event.setCanceled(true);
    }

    public void open(Screen parent) {
        Minecraft.getInstance().setScreen(new RenderScopeScreen(parent, profiler, this));
    }

    private void pauseMenu(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof PauseScreen) || Minecraft.getInstance().level == null) return;
        // Find an unused 100x20 area; leave all vanilla and other mods' buttons intact.
        int x = 6, y = screen.height - 26;
        outer: for (int candidateY = screen.height - 26; candidateY >= 6; candidateY -= 24) {
            for (int candidateX : new int[]{6, Math.max(6, screen.width - 106)}) {
                boolean free = true;
                for (var child : event.getListenersList()) {
                    if (child instanceof AbstractWidget w && w.visible && candidateX < w.getX()+w.getWidth()
                            && candidateX+100 > w.getX() && candidateY < w.getY()+w.getHeight()
                            && candidateY+20 > w.getY()) { free = false; break; }
                }
                if (free) { x=candidateX; y=candidateY; break outer; }
            }
        }
        event.addListener(Button.builder(Component.literal("RenderScope"), button -> open(screen))
                .bounds(x,y,100,20).build());
    }

    private void renderWorld(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            RenderHooks.worldPass(true);
            FlywheelGpuTimers.frameStarted(settings.profiling
                    && settings.mode == ProfilerSettings.MeasurementMode.FULL && hasInstancedRenderer());
        }
        overlay.render(event);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) RenderHooks.worldPass(false);
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
    @Override public boolean hasInstancedRenderer() { return net.minecraftforge.fml.ModList.get().isLoaded("flywheel"); }
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
