package dev.renderscope.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Use the actual render state name, without parsing CompositeRenderType debug output. */
@Mixin(RenderStateShard.class)
public interface RenderTypeNameAccessor {
    @Accessor("name") String renderscope$name();
}
