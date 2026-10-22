package dev.renderscope.loadertest;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

/** Separate test-only mod; never included in the production source set. */
@Mod("renderscope_smoke")
public final class NativeSmokeBootstrap {
    public NativeSmokeBootstrap() {
        TickEvent.ClientTickEvent.Post.BUS.addListener((TickEvent.ClientTickEvent.Post event) -> NativeSmokeTest.tick());
        TickEvent.RenderTickEvent.Post.BUS.addListener((TickEvent.RenderTickEvent.Post event) -> NativeSmokeTest.frame());
    }
}
