package dev.renderscope.modern;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Concrete targets are checked in the selected real Mojang-mapped Minecraft dependency. */
class MappedVanillaTargetsTest {
    private static final String ROOT="net/minecraft/client/renderer/";
    private static final String ENTITY=ROOT+"entity/", BLOCK=ROOT+"blockentity/";
    private static final String SUBMIT="Lcom/mojang/blaze3d/vertex/PoseStack;L"+ROOT+"SubmitNodeCollector;L"+ROOT+"state/CameraRenderState;";
    @Test void entityCallsTargetActualExtractionAndSubmissionOnce() throws IOException {
        exactlyOnce(ENTITY+"EntityRenderDispatcher","extractEntity",ENTITY+"EntityRenderer","createRenderState","(Lnet/minecraft/world/entity/Entity;F)L"+ENTITY+"state/EntityRenderState;");
        exactlyOnce(ENTITY+"EntityRenderDispatcher","submit",ENTITY+"EntityRenderer","submit","(L"+ENTITY+"state/EntityRenderState;"+SUBMIT+")V");
    }
    @Test void blockEntityCallsTargetActualExtractionAndSubmissionOnce() throws IOException {
        exactlyOnce(BLOCK+"BlockEntityRenderDispatcher","tryExtractRenderState",BLOCK+"BlockEntityRenderer","extractRenderState","(Lnet/minecraft/world/level/block/entity/BlockEntity;L"+BLOCK+"state/BlockEntityRenderState;FLnet/minecraft/world/phys/Vec3;L"+ROOT+"feature/ModelFeatureRenderer$CrumblingOverlay;)V");
        exactlyOnce(BLOCK+"BlockEntityRenderDispatcher","submit",BLOCK+"BlockEntityRenderer","submit","(L"+BLOCK+"state/BlockEntityRenderState;"+SUBMIT+")V");
    }
    @Test void worldExtractionPrecedesSameFrameRenderSubmission() throws IOException {
        var calls=calls(ROOT+"LevelRenderer","renderLevel");
        int extract=index(calls,ROOT+"LevelRenderer","extractVisibleEntities");
        int execute=index(calls,"com/mojang/blaze3d/framegraph/FrameGraphBuilder","execute");
        assertTrue(extract>=0 && execute>extract,"World states must be submitted before the next outer runTick");
        assertEquals(1,calls("net/minecraft/client/Minecraft","runTick").stream().filter(call->call.owner().asInternalName().equals(ROOT+"GameRenderer")&&call.name().equalsString("render")).count());
    }
    @Test void screenWrapperOwnsTheSingleBackground() throws IOException {
        assertTrue(calls("net/minecraft/client/gui/screens/Screen","renderWithTooltipAndSubtitles").stream().anyMatch(call->call.name().equalsString("renderBackground")));
        assertFalse(calls("dev/renderscope/modern/RenderScopeScreen","render").stream().anyMatch(call->call.name().equalsString("renderBackground")));
    }
    private static int index(List<InvokeInstruction> calls,String owner,String method) {
        for(int i=0;i<calls.size();i++)if(calls.get(i).owner().asInternalName().equals(owner)&&calls.get(i).name().equalsString(method))return i;
        return -1;
    }
    private static void exactlyOnce(String owner,String method,String target,String name,String descriptor)throws IOException {
        assertEquals(1,calls(owner,method).stream().filter(call->call.owner().asInternalName().equals(target)&&call.name().equalsString(name)&&call.type().equalsString(descriptor)).count(),owner+'.'+method);
    }
    private static List<InvokeInstruction> calls(String owner,String method)throws IOException {
        try(var in=MappedVanillaTargetsTest.class.getClassLoader().getResourceAsStream(owner+".class")) {
            assertNotNull(in,owner);
            return ClassFile.of().parse(in.readAllBytes()).methods().stream().filter(entry->entry.methodName().equalsString(method))
                .flatMap(entry->entry.code().stream()).flatMap(code->code.elementStream()).filter(element->element instanceof InvokeInstruction)
                .map(element->(InvokeInstruction)element).toList();
        }
    }
}
