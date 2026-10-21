package dev.renderscope.modern;

import com.google.gson.JsonObject;
import dev.renderscope.core.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModernReportJsonTest {
    private static final String IDENTITY = "c2ab0462-1c42-42aa-a111-4edc76299991|C:\\private\\world\\secret";
    private static final String DIMENSION = "private:dimension-sentinel";

    @Test void defaultReportStripsInstanceIdentityPositionsAndDimensionIncludingNestedSpike() {
        JsonObject json = ModernReportJson.toJson(capture(), false);
        String text = json.toString();
        assertFalse(text.contains(IDENTITY));
        assertFalse(text.contains("c2ab0462-1c42-42aa-a111-4edc76299991"));
        assertFalse(text.contains("private\\\\world"));
        assertFalse(text.contains(DIMENSION));
        assertFalse(text.contains("123456.75"));
        assertFalse(json.getAsJsonArray("instances").get(0).getAsJsonObject().has("position"));
        assertFalse(json.getAsJsonArray("timeline").get(0).getAsJsonObject().getAsJsonObject("worstInstance").has("dimension"));
        assertFalse(json.get("gpuTimingAvailable").getAsBoolean());
        assertFalse(json.get("geometryAvailable").getAsBoolean());
        JsonObject sources = json.getAsJsonArray("instances").get(0).getAsJsonObject().getAsJsonObject("statistics").getAsJsonObject("sources");
        assertEquals(1, sources.getAsJsonObject("RENDER_EXTRACTION").get("averageMs").getAsDouble());
        assertEquals(3, sources.getAsJsonObject("RENDER_SUBMISSION").get("averageMs").getAsDouble());
    }
    @Test void OptInAllowsPositionsButNeverExposesOpaqueIdentityOrAbsolutePath() {
        JsonObject json = ModernReportJson.toJson(capture(), true);
        JsonObject instance = json.getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(123456.75, instance.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(DIMENSION, instance.get("dimension").getAsString());
        assertEquals(DIMENSION, json.getAsJsonArray("timeline").get(0).getAsJsonObject().getAsJsonObject("worstInstance").get("dimension").getAsString());
        assertFalse(json.toString().contains("c2ab0462"));
        assertFalse(json.toString().contains("C:"));
        assertFalse(instance.has("identity"));
    }
    private static CaptureReport capture() {
        SampleContext context = new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY, DIMENSION, IDENTITY),
            "minecraft:chest", "minecraft", "example.ChestRenderer", "example", new Position(123456.75,64,-5),
            new Bounds(123456,64,-5,123457,65,-4));
        Statistics stats = new Statistics(4,4,4,3,2,2,0,0,0,1,Set.of(),Map.of(
            MeasurementSource.RENDER_EXTRACTION,new SourceMetric(1,1,1,1,0),
            MeasurementSource.RENDER_SUBMISSION,new SourceMetric(3,2,1,1,0)));
        InstanceMetric instance = new InstanceMetric(context,stats,1);
        AggregateMetric mod = new AggregateMetric("minecraft",null,"minecraft","example.ChestRenderer","example",stats);
        Performance performance = new Performance(1,60,16,16,60,16,16,16,16,4,0,4,2,0,1,1,0,0);
        return new CaptureReport(0,1,1,performance,List.of(mod),List.of(mod),List.of(mod),List.of(instance),
            List.of(new FramePoint(1,40,4,0,4,true,Map.of("minecraft",4.0),instance)),0,List.of());
    }
}
