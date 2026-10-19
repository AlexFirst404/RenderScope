package dev.renderscope;

import dev.renderscope.client.ClientRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(RenderScope.MOD_ID)
public final class RenderScope {
    public static final String MOD_ID = "renderscope";

    public RenderScope() {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> ClientRuntime::initialize);
    }
}
