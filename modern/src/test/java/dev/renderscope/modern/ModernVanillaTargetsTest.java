package dev.renderscope.modern;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies interception contracts against the actual Minecraft dependency, without booting a fake client. */
class ModernVanillaTargetsTest {
    private static final String ENTITY = "net/minecraft/client/renderer/entity/";
    private static final String BLOCK = "net/minecraft/client/renderer/blockentity/";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String COLLECTOR = "Lnet/minecraft/client/renderer/SubmitNodeCollector;";
    private static final String CAMERA = "Lnet/minecraft/client/renderer/state/level/CameraRenderState;";
    @Test void entityExtractionAndSubmissionEachHaveExactlyOneConcreteRendererInvocation() throws IOException {
        assertCall(ENTITY+"EntityRenderDispatcher", "extractEntity", ENTITY+"EntityRenderer", "createRenderState",
            "(Lnet/minecraft/world/entity/Entity;F)L"+ENTITY+"state/EntityRenderState;");
        assertCall(ENTITY+"EntityRenderDispatcher", "submit", ENTITY+"EntityRenderer", "submit",
            "(L"+ENTITY+"state/EntityRenderState;"+POSE+COLLECTOR+CAMERA+")V");
    }
    @Test void blockEntityExtractionAndSubmissionEachHaveExactlyOneConcreteRendererInvocation() throws IOException {
        assertCall(BLOCK+"BlockEntityRenderDispatcher", "tryExtractRenderState", BLOCK+"BlockEntityRenderer", "extractRenderState",
            "(Lnet/minecraft/world/level/block/entity/BlockEntity;L"+BLOCK+"state/BlockEntityRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V");
        assertCall(BLOCK+"BlockEntityRenderDispatcher", "submit", BLOCK+"BlockEntityRenderer", "submit",
            "(L"+BLOCK+"state/BlockEntityRenderState;"+POSE+COLLECTOR+CAMERA+")V");
    }
    @Test void vanillaExtractsAndSubmitsWithinTheSameFrameBeforeStateAssociationsAreCleared() throws IOException {
        List<InvokeInstruction> calls = calls("net/minecraft/client/Minecraft", "renderFrame");
        int extract = indexOf(calls,"net/minecraft/client/renderer/GameRenderer","extract");
        int render = indexOf(calls,"net/minecraft/client/renderer/GameRenderer","render");
        assertTrue(extract>=0 && render>extract,"This adapter requires same-frame extraction before rendering");
        if (ModernVanillaTargetsTest.class.getClassLoader().getResource("net/minecraft/client/renderer/extract/LevelExtractor.class") != null) {
            assertCall("net/minecraft/client/renderer/extract/LevelExtractor","extract", "net/minecraft/client/renderer/extract/LevelExtractor","extractGizmos","()V");
        } else {
            assertTrue(calls("net/minecraft/client/renderer/LevelRenderer","extractLevel").stream()
                .anyMatch(call -> call.name().equalsString("extractVisibleEntities")));
        }
    }
    @Test void nativeScreenWrapperOwnsTheSingleBackgroundExtraction() throws IOException {
        assertTrue(calls("net/minecraft/client/gui/screens/Screen", "extractRenderStateWithTooltipAndSubtitles").stream()
            .anyMatch(call -> call.name().equalsString("extractBackground")), "Background contract changed");
        assertFalse(calls("dev/renderscope/modern/RenderScopeScreen", "extractRenderState").stream()
            .anyMatch(call -> call.name().equalsString("extractBackground")), "A second blur crashes the new UI pipeline");
    }
    private static int indexOf(List<InvokeInstruction> calls,String owner,String method) {
        for(int i=0;i<calls.size();i++) if(calls.get(i).owner().asInternalName().equals(owner)&&calls.get(i).name().equalsString(method)) return i;
        return -1;
    }
    private static void assertCall(String container,String method,String owner,String name,String type) throws IOException {
        assertEquals(1,calls(container,method).stream().filter(call->call.owner().asInternalName().equals(owner)
            &&call.name().equalsString(name)&&call.type().equalsString(type)).count(),container+'.'+method+" interception changed");
    }
    private static List<InvokeInstruction> calls(String owner,String method) throws IOException {
        try(var input=ModernVanillaTargetsTest.class.getClassLoader().getResourceAsStream(owner+".class")) {
            assertNotNull(input,"Missing actual Minecraft class "+owner);
            ClassModel model=ClassFile.of().parse(input.readAllBytes());
            return model.methods().stream().filter(entry->entry.methodName().equalsString(method))
                .flatMap(entry->entry.code().stream()).flatMap(code->code.elementStream())
                .filter(element->element instanceof InvokeInstruction).map(element->(InvokeInstruction)element).toList();
        }
    }
}
