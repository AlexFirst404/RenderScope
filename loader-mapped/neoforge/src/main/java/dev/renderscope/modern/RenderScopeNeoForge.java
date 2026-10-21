package dev.renderscope.modern;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod("renderscope")
public final class RenderScopeNeoForge {
    public RenderScopeNeoForge(IEventBus modBus) {
        if (FMLEnvironment.getDist() == Dist.CLIENT) NeoForgeModernBootstrap.initialize(modBus);
    }
}
