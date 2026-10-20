package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.renderscope.client.RenderHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Mekanism 10.4 deferred translucent renderer support. No Mekanism types enter our
 * descriptors, so clients without the mod keep the ordinary dispatcher instrumentation.
 */
@Pseudo
@Mixin(targets = "mekanism.client.render.RenderTickHandler", remap = false)
public abstract class MekanismDeferredRenderMixin {
    @Inject(method = "addTransparentRenderer", at = @At("HEAD"), remap = false, require = 0)
    private static void renderscope$queue(RenderType type, @Coerce Object task, CallbackInfo ci) {
        RenderHooks.rememberDeferred(task, ((RenderTypeNameAccessor) type).renderscope$name());
    }

    @WrapOperation(method = "lambda$renderWorld$1", at = @At(value = "INVOKE", target =
        "Lmekanism/client/render/RenderTickHandler$LazyRender;render(Lnet/minecraft/client/Camera;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/PoseStack;IFLnet/minecraft/util/profiling/ProfilerFiller;)V",
        remap = false), remap = false, require = 0)
    private static void renderscope$measure(@Coerce Object task, Camera camera, VertexConsumer consumer,
                                            PoseStack poses, int renderTick, float partialTick,
                                            ProfilerFiller profiler, Operation<Void> original) {
        RenderHooks.Scope scope = RenderHooks.beginDeferred(task);
        boolean failed = true;
        try {
            original.call(task, camera, consumer, poses, renderTick, partialTick, profiler);
            failed = false;
        } finally {
            RenderHooks.endDeferred(scope, failed);
        }
    }
}
