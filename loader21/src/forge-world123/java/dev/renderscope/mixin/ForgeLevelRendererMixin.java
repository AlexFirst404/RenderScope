package dev.renderscope.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.client.ClientRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;

/** Forge no longer publishes RenderLevelStageEvent; use the actual native world call. */
@Mixin(LevelRenderer.class)
public abstract class ForgeLevelRendererMixin {
    @Shadow private Frustum cullingFrustum;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void renderscope$begin(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        ClientRuntime runtime = ClientRuntime.current();
        if (runtime != null) runtime.beginWorldPass();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void renderscope$finish(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera, GameRenderer renderer, LightTexture light, Matrix4f view, Matrix4f projection, CallbackInfo ci) {
        ClientRuntime runtime = ClientRuntime.current();
        if (runtime == null) return;
        try {
            dev.renderscope.client.platform.NativeWorldOverlay.render(runtime, view, projection, camera.getPosition(), cullingFrustum,
                    delta.getGameTimeDeltaPartialTick(false));
        } finally {
            runtime.endWorldPass();
        }
    }
}
