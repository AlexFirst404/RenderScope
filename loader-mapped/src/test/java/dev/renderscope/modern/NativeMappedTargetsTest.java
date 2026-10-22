package dev.renderscope.modern;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Checks concrete interception sites in the actual patched Minecraft dependency for each loader. */
class NativeMappedTargetsTest {
    private static final String ROOT="net/minecraft/client/renderer/";
    private static final String ENTITY=ROOT+"entity/", BLOCK=ROOT+"blockentity/";
    private static final String SUBMIT="Lcom/mojang/blaze3d/vertex/PoseStack;L"+ROOT+"SubmitNodeCollector;L"+ROOT+"state/CameraRenderState;";
    private record Call(String owner,String name,String descriptor) { }
    @Test void entityConcreteExtractionAndSubmissionCallsArePresentOnce() throws IOException {
        exactlyOnce(ENTITY+"EntityRenderDispatcher","extractEntity",ENTITY+"EntityRenderer","createRenderState","(Lnet/minecraft/world/entity/Entity;F)L"+ENTITY+"state/EntityRenderState;");
        exactlyOnce(ENTITY+"EntityRenderDispatcher","submit",ENTITY+"EntityRenderer","submit","(L"+ENTITY+"state/EntityRenderState;"+SUBMIT+")V");
    }
    @Test void blockEntityConcreteExtractionAndSubmissionCallsArePresentOnce() throws IOException {
        String selector=extractionSelector();
        if(!selector.contains("(")) selector=firstOverloadSelector(BLOCK+"BlockEntityRenderDispatcher",selector);
        assertEquals(1,calls(BLOCK+"BlockEntityRenderDispatcher",selector).stream()
            .filter(call->call.owner.equals(BLOCK+"BlockEntityRenderer")&&call.name.equals("extractRenderState")).count(),
            "The actual compiled Mixin selector must reach the renderer invocation, not a delegating overload");
        exactlyOnce(BLOCK+"BlockEntityRenderDispatcher","tryExtractRenderState",BLOCK+"BlockEntityRenderer","extractRenderState","(Lnet/minecraft/world/level/block/entity/BlockEntity;L"+BLOCK+"state/BlockEntityRenderState;FLnet/minecraft/world/phys/Vec3;L"+ROOT+"feature/ModelFeatureRenderer$CrumblingOverlay;)V");
        exactlyOnce(BLOCK+"BlockEntityRenderDispatcher","submit",BLOCK+"BlockEntityRenderer","submit","(L"+BLOCK+"state/BlockEntityRenderState;"+SUBMIT+")V");
    }
    private static String firstOverloadSelector(String owner,String method)throws IOException {
        List<String> matches=new ArrayList<>();
        try(var input=NativeMappedTargetsTest.class.getClassLoader().getResourceAsStream(owner+".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(name.equals(method))matches.add(name+descriptor);return null;
                }
            },ClassReader.SKIP_CODE);
        }
        assertFalse(matches.isEmpty());return matches.getFirst();
    }
    private static String extractionSelector() throws IOException {
        List<String> selectors=new ArrayList<>();
        try(var input=NativeMappedTargetsTest.class.getClassLoader().getResourceAsStream("dev/renderscope/modern/mixin/BlockEntityDispatcherMixin.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.equals("renderscope$extract"))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String type,boolean visible) {
                            if(!type.endsWith("/WrapOperation;"))return null;
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override public AnnotationVisitor visitArray(String name) {
                                    if(!name.equals("method"))return null;
                                    return new AnnotationVisitor(Opcodes.ASM9) {
                                        @Override public void visit(String name,Object value) {selectors.add((String)value);}
                                    };
                                }
                            };
                        }
                    };
                }
            },ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        assertEquals(1,selectors.size());return selectors.getFirst();
    }
    @Test void worldExtractsBeforeExecutingItsSameFrameSubmissionGraph() throws IOException {
        var calls=calls(ROOT+"LevelRenderer","renderLevel");
        int extract=index(calls,ROOT+"LevelRenderer","extractVisibleEntities");
        int execute=index(calls,"com/mojang/blaze3d/framegraph/FrameGraphBuilder","execute");
        assertTrue(extract>=0 && execute>extract,"Retained state associations require same-frame submission");
        assertEquals(1,calls("net/minecraft/client/Minecraft","runTick").stream().filter(call->call.owner.equals(ROOT+"GameRenderer")&&call.name.equals("render")).count());
    }
    @Test void screenWrapperOwnsSingleBackgroundWithoutRepeatedBlur() throws IOException {
        assertTrue(calls("net/minecraft/client/gui/screens/Screen","renderWithTooltipAndSubtitles").stream().anyMatch(call->call.name.equals("renderBackground")));
        assertFalse(calls("dev/renderscope/modern/RenderScopeScreen","render").stream().anyMatch(call->call.name.equals("renderBackground")));
    }
    private static int index(List<Call> calls,String owner,String name) {
        for(int i=0;i<calls.size();i++)if(calls.get(i).owner.equals(owner)&&calls.get(i).name.equals(name))return i;
        return -1;
    }
    private static void exactlyOnce(String owner,String method,String target,String name,String descriptor)throws IOException {
        assertEquals(1,calls(owner,method).stream().filter(call->call.owner.equals(target)&&call.name.equals(name)&&call.descriptor.equals(descriptor)).count(),owner+'.'+method);
    }
    private static List<Call> calls(String owner,String method)throws IOException {
        try(var input=NativeMappedTargetsTest.class.getClassLoader().getResourceAsStream(owner+".class")) {
            assertNotNull(input,owner);
            List<Call> result=new ArrayList<>();
            int separator=method.indexOf('(');
            String selectedName=separator<0?method:method.substring(0,separator);
            String selectedDescriptor=separator<0?null:method.substring(separator);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                    if(!name.equals(selectedName)||(selectedDescriptor!=null&&!descriptor.equals(selectedDescriptor)))return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode,String target,String called,String type,boolean isInterface) {result.add(new Call(target,called,type));}
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            return result;
        }
    }
}
