package dev.renderscope.client.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.renderscope.core.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReportExporterTest {
    private static final String PRIVATE_IDENTITY = "123e4567-e89b-12d3-a456-426614174000|C:\\Sensitive\\PlayerSecret\\world-save";
    private static final String PRIVATE_SPIKE_IDENTITY = "987e6543-e21b-12d3-a456-426614174000|/home/secretplayer/.minecraft/world";
    private static final String DIMENSION = "private_world:dimension_sentinel";
    private static final String SPIKE_DIMENSION = "private_world:spike_dimension_sentinel";

    @Test
    void defaultReportDoesNotLeakOpaqueIdentityCoordinatesDimensionOrAbsolutePathsEvenInSpikes() {
        JsonObject report = ReportExporter.toJson(capture(), false, "Timing Only", false);
        String serialized = report.toString();
        assertFalse(report.get("positionsIncluded").getAsBoolean());
        assertNoIdentityOrPaths(serialized);
        assertFalse(serialized.contains(DIMENSION));
        assertFalse(serialized.contains(SPIKE_DIMENSION));
        assertFalse(serialized.contains("876543.125"));
        assertFalse(serialized.contains("-987654.75"));
        assertFalse(serialized.contains("345678.875"));
        assertFalse(serialized.contains("765432.25"));
        assertNoFieldsRecursively(report, Set.of("position", "dimension", "identity", "key", "bounds",
            "lastSeenNanos", "playerUsername", "serverIp", "serverHostname", "worldName", "chat", "uuid"));
        JsonObject instance = report.getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals("minecraft:chest", instance.get("registryId").getAsString());
        assertEquals(2.5, instance.getAsJsonObject("statistics").get("averageMs").getAsDouble());
        JsonObject spike = report.getAsJsonArray("timeline").get(0).getAsJsonObject();
        assertTrue(spike.get("spike").getAsBoolean());
        assertTrue(spike.has("worstInstance"), "Privacy must preserve anonymous performance attribution");
    }

    @Test
    void explicitPositionOptInIncludesPositionAndDimensionButNeverOpaqueIdentityOrPaths() {
        JsonObject report = ReportExporter.toJson(capture(), true, "Full", true);
        String serialized = report.toString();
        assertTrue(report.get("positionsIncluded").getAsBoolean());
        assertNoIdentityOrPaths(serialized);
        assertNoFieldsRecursively(report, Set.of("identity", "key", "bounds", "lastSeenNanos", "uuid"));
        JsonObject instance = report.getAsJsonArray("instances").get(0).getAsJsonObject();
        assertEquals(DIMENSION, instance.get("dimension").getAsString());
        assertEquals(876543.125, instance.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(65432.5, instance.getAsJsonObject("position").get("y").getAsDouble());
        assertEquals(-987654.75, instance.getAsJsonObject("position").get("z").getAsDouble());
        JsonObject spikeInstance = report.getAsJsonArray("timeline").get(0).getAsJsonObject().getAsJsonObject("worstInstance");
        assertEquals(SPIKE_DIMENSION, spikeInstance.get("dimension").getAsString());
        assertEquals(345678.875, spikeInstance.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(765432.25, spikeInstance.getAsJsonObject("position").get("z").getAsDouble());
        assertEquals(1, report.getAsJsonArray("mods").size());
        assertEquals(1, report.getAsJsonArray("renderers").size());
        assertEquals(1, report.getAsJsonArray("objects").size());
    }

    private static void assertNoIdentityOrPaths(String json) {
        for (String privateValue : List.of("123e4567-e89b-12d3-a456-426614174000",
            "987e6543-e21b-12d3-a456-426614174000", "Sensitive", "PlayerSecret", "world-save", "/home/secretplayer")) {
            assertFalse(json.contains(privateValue), "Private value leaked: " + privateValue);
        }
    }

    private static void assertNoFieldsRecursively(JsonElement element, Set<String> names) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
                assertFalse(names.contains(field.getKey()), "Forbidden field leaked: " + field.getKey());
                assertNoFieldsRecursively(field.getValue(), names);
            }
        } else if (element instanceof JsonArray array) {
            for (JsonElement child : array) assertNoFieldsRecursively(child, names);
        }
    }

    private static CaptureReport capture() {
        Statistics stats = new Statistics(2.5, 3.5, 5.5, 2.0, 1.0, 100, 1,
            32, 1024, 1, Set.of("solid", "translucent"));
        SampleContext context = new SampleContext(new InstanceKey(ObjectKind.BLOCK_ENTITY, DIMENSION, PRIVATE_IDENTITY),
            "minecraft:chest", "minecraft", "compat.ChestRenderer", "compat",
            new Position(876543.125, 65432.5, -987654.75), new Bounds(1, 2, 3, 4, 5, 6));
        SampleContext spikeContext = new SampleContext(new InstanceKey(ObjectKind.ENTITY, SPIKE_DIMENSION, PRIVATE_SPIKE_IDENTITY),
            "minecraft:cow", "minecraft", "compat.CowRenderer", "compat",
            new Position(345678.875, 99.5, 765432.25), new Bounds(10, 20, 30, 40, 50, 60));
        InstanceMetric instance = new InstanceMetric(context, stats, 123456789012345L);
        InstanceMetric spikeInstance = new InstanceMetric(spikeContext, stats, 123456789012346L);
        AggregateMetric mod = new AggregateMetric("minecraft", null, "minecraft", "compat.ChestRenderer", "compat", stats);
        AggregateMetric renderer = new AggregateMetric("compat.ChestRenderer", null, "minecraft", "compat.ChestRenderer", "compat", stats);
        AggregateMetric object = new AggregateMetric("minecraft:chest", ObjectKind.BLOCK_ENTITY, "minecraft", "compat.ChestRenderer", "compat", stats);
        Performance performance = new Performance(100, 100, 10, 10, 50, 10, 15, 20, 20,
            2.5, 1.0, 1.5, 1.0, 0.5, 0.5, 2, 32, 1024);
        FramePoint spike = new FramePoint(123456789012346L, 20, 5, 2, 3, true, Map.of("minecraft", 5.0), spikeInstance);
        return new CaptureReport(1_700_000_000_000L, 1, 100, performance, List.of(mod), List.of(renderer),
            List.of(object), List.of(instance), List.of(spike), 0);
    }
}
