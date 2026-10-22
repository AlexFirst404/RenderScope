package dev.renderscope.client.platform;

import dev.renderscope.client.ClientRuntime;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class NeoForgeClientBootstrap {
    private NeoForgeClientBootstrap() { }
    public static void initialize(IEventBus modBus) {
        ClientRuntime runtime = ClientRuntime.initialize(new NeoForgePlatform());
        modBus.addListener((RegisterKeyMappingsEvent event) -> event.register(ClientRuntime.openKey()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> runtime.clientTick());
        NeoForge.EVENT_BUS.addListener((ScreenEvent.KeyPressed.Pre event) -> {
            if (runtime.screenKey(event.getScreen(), event.getKeyCode(), event.getScanCode())) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) ->
                runtime.pauseMenu(event.getScreen(), event.getListenersList(), event::addListener));
        // The typed stage event no longer carries a projection matrix. The native
        // LevelRenderer adapter brackets the pass and supplies its exact matrices.
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> runtime.overlay().renderHud(event.getGuiGraphics()));
    }
}
