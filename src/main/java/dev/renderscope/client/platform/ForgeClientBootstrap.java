package dev.renderscope.client.platform;

import dev.renderscope.client.ClientRuntime;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Loader-specific event registration; all profiler/UI state remains shared. */
public final class ForgeClientBootstrap {
    private ForgeClientBootstrap() { }
    public static void initialize() {
        ClientRuntime runtime = ClientRuntime.initialize(new ForgePlatform());
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent event) ->
                event.register(ClientRuntime.openKey()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) runtime.clientTick();
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (runtime.screenKey(event.getScreen(), event.getKeyCode(), event.getScanCode())) event.setCanceled(true);
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) ->
                runtime.pauseMenu(event.getScreen(), event.getListenersList(), event::addListener));
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) runtime.beginWorldPass();
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES)
                runtime.overlay().render(event.getPoseStack(), event.getProjectionMatrix(),
                        event.getCamera().getPosition(), event.getFrustum(), event.getPartialTick());
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) runtime.endWorldPass();
        });
        MinecraftForge.EVENT_BUS.addListener((RenderGuiOverlayEvent.Post event) -> {
            if (event.getOverlay().id().equals(VanillaGuiOverlay.DEBUG_TEXT.id())) runtime.overlay().renderHud(event.getGuiGraphics());
        });
    }
}
