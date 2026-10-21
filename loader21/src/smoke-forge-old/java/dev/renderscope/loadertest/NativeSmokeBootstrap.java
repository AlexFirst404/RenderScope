package dev.renderscope.loadertest;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

/** Source-only fixture driven by the actual pre-1.20.5 native event API. */
@Mod("renderscope_smoke")
public final class NativeSmokeBootstrap {
    public NativeSmokeBootstrap() {
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) NativeSmokeTest.tick();
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) NativeSmokeTest.frame();
        });
    }
}
