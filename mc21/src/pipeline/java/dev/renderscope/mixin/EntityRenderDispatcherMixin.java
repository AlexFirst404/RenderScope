package dev.renderscope.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.client.ClientRuntime;
import dev.renderscope.client.PreparedContexts;
import dev.renderscope.client.RenderHooks;
import dev.renderscope.core.MeasurementSource;
import dev.renderscope.core.SampleContext;
import dev.renderscope.core.RenderProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Associate pooled renderer state with value metadata and the frame that actually prepared it. */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Unique private static final PreparedContexts renderscope$prepared = new PreparedContexts();

    @WrapOperation(method="render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/EntityRenderer;)V", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/entity/EntityRenderer;createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState renderscope$extract(EntityRenderer<?, ?> renderer, Entity entity, float partialTick,
                                                 Operation<EntityRenderState> original) {
        ClientRuntime runtime = ClientRuntime.current();
        var level = Minecraft.getInstance().level;
        SampleContext context = runtime != null && level != null && entity.level() == level
                && level.getEntity(entity.getId()) == entity ? RenderHooks.context(entity, renderer) : null;
        RenderProfiler.FrameHandle frame = runtime == null ? null : runtime.profiler().currentFrame();
        RenderHooks.Scope scope = RenderHooks.beginPrepared(context, renderer, MeasurementSource.RENDER_EXTRACTION, frame, false);
        boolean failed = true;
        EntityRenderState state = null;
        try {
            state = original.call(renderer, entity, partialTick);
            failed = false;
            return state;
        } finally {
            RenderHooks.end(scope, failed);
            if (!failed && scope != null && !renderscope$prepared.put(state, context, frame)) runtime.profiler().recordDroppedMeasurement();
        }
    }

    @WrapOperation(method="render(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/EntityRenderer;)V", at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
    private void renderscope$render(EntityRenderer<?, ?> renderer, EntityRenderState state, PoseStack poses,
                                    MultiBufferSource buffers, int light, Operation<Void> original) {
        PreparedContexts.Prepared prepared = renderscope$prepared.get(state);
        RenderHooks.Scope scope = prepared == null ? null : RenderHooks.beginPrepared(prepared.context(), renderer,
                MeasurementSource.RENDERER, prepared.frame(), true);
        boolean failed = true;
        try {
            original.call(renderer, state, poses, buffers, light);
            failed = false;
        } finally { RenderHooks.end(scope, failed); }
    }
}
