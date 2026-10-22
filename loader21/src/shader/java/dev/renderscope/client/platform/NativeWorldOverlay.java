package dev.renderscope.client.platform;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.client.ClientRuntime;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/** Draw an explicit native world-view matrix without applying the global view twice. */
public final class NativeWorldOverlay {
    private NativeWorldOverlay() { }

    public static void render(ClientRuntime runtime, Matrix4f view, Matrix4f projection,
                              Vec3 camera, Frustum frustum, float partialTick) {
        PoseStack pose = new PoseStack();
        pose.mulPose(view);
        // Native shader renderers keep the camera transform in the global model-view
        // uniform too. Our vertices already use the event's complete view matrix;
        // retain that same matrix for both label projection and geometry submission.
        Matrix4fStack global = RenderSystem.getModelViewStack();
        global.pushMatrix();
        try {
            global.identity();
            RenderSystem.applyModelViewMatrix();
            runtime.overlay().render(pose, projection, camera, frustum, partialTick);
        } finally {
            global.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
