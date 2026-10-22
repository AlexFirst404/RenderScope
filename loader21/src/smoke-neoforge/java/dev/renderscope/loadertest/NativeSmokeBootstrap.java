package dev.renderscope.loadertest;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;

/** Separate test-only mod; never included in the production source set. */
@Mod("renderscope_smoke")
public final class NativeSmokeBootstrap {
    public NativeSmokeBootstrap() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> NativeSmokeTest.tick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> NativeSmokeTest.frame());
    }
}
