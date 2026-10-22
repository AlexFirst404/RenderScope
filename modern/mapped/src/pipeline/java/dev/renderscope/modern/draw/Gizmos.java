package dev.renderscope.modern.draw;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.renderscope.client.integration.ShaderPasses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/** RenderScope-owned compatibility renderer, not a fictional Minecraft Gizmos API. */
public final class Gizmos {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(65536));
    private Gizmos() { }
    private record Box(AABB bounds,GizmoStyle style,GizmoProperties properties) { }
    private record Text(String value,Vec3 position,TextGizmo.Style style,GizmoProperties properties) { }
    private record Context(Matrix4f view,Vec3 camera,Quaternionf rotation,List<Box> boxes,List<Text> labels) { }

    public static void render(Matrix4f view, Vec3 camera, Quaternionf rotation, Runnable populate) {
        if (ShaderPasses.isShadowPass()) return;
        if (CURRENT.get()!=null) throw new IllegalStateException("Nested RenderScope overlay pass");
        CURRENT.set(new Context(view,camera,rotation,new ArrayList<>(),new ArrayList<>()));
        try { populate.run(); }
        finally { CURRENT.remove(); }
    }
    public static TemporaryCollection collect() {
        Context context=CURRENT.get();
        if(context==null) throw new IllegalStateException("Overlay collection outside its actual world pass");
        return new TemporaryCollection(context);
    }
    public static GizmoProperties cuboid(AABB bounds,GizmoStyle style) {
        Context context=CURRENT.get();
        if(context==null) throw new IllegalStateException("No overlay pass");
        GizmoProperties properties=new GizmoProperties();
        if(context.boxes.size()<500)context.boxes.add(new Box(bounds,style,properties));
        return properties;
    }
    public static GizmoProperties billboardText(String text,Vec3 position,TextGizmo.Style style) {
        Context context=CURRENT.get();
        if(context==null) throw new IllegalStateException("No overlay pass");
        GizmoProperties properties=new GizmoProperties();
        if(context.labels.size()<1000)context.labels.add(new Text(text,position,style,properties));
        return properties;
    }
    public static final class TemporaryCollection implements AutoCloseable {
        private Context context;
        private TemporaryCollection(Context context) { this.context=context; }
        @Override public void close() {
            Context current=context;
            if(current==null)return;
            context=null;
            PoseStack pose=new PoseStack();
            pose.mulPose(current.view);
            pose.translate(-current.camera.x,-current.camera.y,-current.camera.z);
            try {
                for(Box box:current.boxes) {
                    int color=box.style.color();
                    ShapeRenderer.renderLineBox(pose.last(),BUFFERS.getBuffer(box.properties.throughWalls?Lines.THROUGH:Lines.DEPTH),box.bounds,
                        ((color>>>16)&255)/255f,((color>>>8)&255)/255f,(color&255)/255f,((color>>>24)&255)/255f);
                }
                BUFFERS.endBatch();
                Font font=Minecraft.getInstance().font;
                for(Text text:current.labels) {
                    pose.pushPose();
                    try {
                        pose.translate(text.position.x,text.position.y,text.position.z);
                        pose.mulPose(current.rotation);
                        float scale=text.style.scale()/font.lineHeight;
                        pose.scale(scale,-scale,scale);
                        font.drawInBatch(text.value,-font.width(text.value)/2f,0,text.style.color(),false,pose.last().pose(),BUFFERS,
                            text.properties.throughWalls?Font.DisplayMode.SEE_THROUGH:Font.DisplayMode.NORMAL,0x66000000,0xf000f0);
                    } finally {pose.popPose();}
                }
            } finally {BUFFERS.endBatch();}
        }
    }
    private static final class Lines {
        private static final RenderType DEPTH=create(false),THROUGH=create(true);
        private static RenderType create(boolean through) {
            RenderPipeline pipeline=RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath("renderscope","mapped_outlines_"+through))
                .withDepthTestFunction(through?DepthTestFunction.NO_DEPTH_TEST:DepthTestFunction.LEQUAL_DEPTH_TEST)
                .withDepthWrite(false).withCull(false).build());
            return RenderType.create("renderscope_mapped_outlines_"+through,65536,pipeline,RenderType.CompositeState.builder().createCompositeState(false));
        }
    }
}
