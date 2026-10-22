package dev.renderscope.client.platform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.renderscope.client.ClientRuntime;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** Keep the explicit view separate from the model-view read directly by native render types. */
public final class NativeWorldOverlay {
    private NativeWorldOverlay() { }

    public static void render(ClientRuntime runtime, Matrix4f view, Matrix4f projection,
                              Vec3 camera, Frustum frustum, float partialTick) {
        PoseStack pose = new PoseStack();
        pose.mulPose(view);
        // Since 1.21.2 RenderSystem.getModelViewMatrix returns this stack directly.
        // Pipeline render types also read it into DynamicUniforms at draw time.
        Matrix4fStack global = RenderSystem.getModelViewStack();
        global.pushMatrix();
        try {
            global.identity();
            runtime.overlay().render(pose, projection, camera, frustum, partialTick);
        } finally {
            global.popMatrix();
        }
    }
}
