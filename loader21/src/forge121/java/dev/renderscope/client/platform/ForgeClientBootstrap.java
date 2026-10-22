package dev.renderscope.client.platform;

import dev.renderscope.client.ClientRuntime;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Forge 51/52 events expose an entity-local pose, with the camera in the active global view. */
public final class ForgeClientBootstrap {
    private ForgeClientBootstrap() { }
    public static void initialize() {
        ClientRuntime runtime = ClientRuntime.initialize(new ForgePlatform());
        FMLJavaModLoadingContext.get().getModEventBus().addListener((RegisterKeyMappingsEvent event) ->
                event.register(ClientRuntime.openKey()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent.Post event) -> runtime.clientTick());
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (runtime.screenKey(event.getScreen(), event.getKeyCode(), event.getScanCode())) event.setCanceled(true);
        });
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) ->
                runtime.pauseMenu(event.getScreen(), event.getListenersList(), event::addListener));
        MinecraftForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) runtime.beginWorldPass();
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                // Forge 51/52 pass the entity-local pose and the camera view as
                // the event's two matrices. Recover the actual shader transform:
                // global camera view * local pose, paired with the active projection.
                Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(event.getPoseStack());
                NativeWorldOverlay.render(runtime, view, new Matrix4f(RenderSystem.getProjectionMatrix()),
                        event.getCamera().getPosition(), event.getFrustum(), event.getPartialTick());
            }
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) runtime.endWorldPass();
        });
    }
}
