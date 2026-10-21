package dev.renderscope.modern.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.renderscope.modern.ModernHooks;
import dev.renderscope.modern.ModernOverlay;
import dev.renderscope.modern.draw.Gizmos;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class MappedLevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD")) private void renderscope$worldStart(CallbackInfo ci) { ModernHooks.enterWorld(); }
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void renderscope$worldEnd(GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline, Camera camera,
        Matrix4f view, Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        try { Gizmos.render(view,camera.getPosition(),camera.rotation(),ModernOverlay::emitWorldGizmos); }
        finally {ModernHooks.leaveWorld();}
    }
}
