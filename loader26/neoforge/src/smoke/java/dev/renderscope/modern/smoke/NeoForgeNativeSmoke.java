package dev.renderscope.modern.smoke;

@net.neoforged.fml.common.Mod("renderscope_native_smoke")
public final class NeoForgeNativeSmoke {
    public NeoForgeNativeSmoke() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> NativeSmokeContext.tick());
    }
}
