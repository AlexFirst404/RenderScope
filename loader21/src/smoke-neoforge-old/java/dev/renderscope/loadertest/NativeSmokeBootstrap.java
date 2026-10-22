package dev.renderscope.loadertest;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.fml.common.Mod;

/** Source-only fixture driven by the actual pre-1.20.5 native event API. */
@Mod("renderscope_smoke")
public final class NativeSmokeBootstrap {
    public NativeSmokeBootstrap() {
        NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) NativeSmokeTest.tick();
        });
        NeoForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) NativeSmokeTest.frame();
        });
    }
}
