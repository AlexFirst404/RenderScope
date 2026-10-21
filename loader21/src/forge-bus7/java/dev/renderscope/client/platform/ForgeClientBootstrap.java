package dev.renderscope.client.platform;

import dev.renderscope.client.ClientRuntime;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Loader-specific event registration; all profiler/UI state remains shared. */
public final class ForgeClientBootstrap {
    private ForgeClientBootstrap() { }
    public static void initialize() {
        ClientRuntime runtime = ClientRuntime.initialize(new ForgePlatform());
        RegisterKeyMappingsEvent.getBus(FMLJavaModLoadingContext.get().getModBusGroup()).addListener((RegisterKeyMappingsEvent event) ->
                event.register(ClientRuntime.openKey()));
        TickEvent.ClientTickEvent.Post.BUS.addListener((TickEvent.ClientTickEvent.Post event) -> runtime.clientTick());
        ScreenEvent.KeyPressed.Pre.BUS.addListener((java.util.function.Predicate<ScreenEvent.KeyPressed.Pre>) event ->
                runtime.screenKey(event.getScreen(), event.getKeyCode(), event.getScanCode()));
        ScreenEvent.Init.Post.BUS.addListener((ScreenEvent.Init.Post event) ->
                runtime.pauseMenu(event.getScreen(), event.getListenersList(), event::addListener));
    }
}
