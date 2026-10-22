package dev.renderscope.modern;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("renderscope")
public final class RenderScopeForge {
    public RenderScopeForge(FMLJavaModLoadingContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeModernBootstrap.initialize();
    }
}
