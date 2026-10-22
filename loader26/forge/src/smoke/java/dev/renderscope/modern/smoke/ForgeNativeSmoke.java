package dev.renderscope.modern.smoke;

@net.minecraftforge.fml.common.Mod("renderscope_native_smoke")
public final class ForgeNativeSmoke {
    public ForgeNativeSmoke(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext context) {
        net.minecraftforge.event.TickEvent.ClientTickEvent.Post.BUS.addListener(event -> NativeSmokeContext.tick());
    }
}
