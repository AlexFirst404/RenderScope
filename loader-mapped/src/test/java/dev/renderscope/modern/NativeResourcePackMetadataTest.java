package dev.renderscope.modern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Checks the upstream native hidden-pack pattern against both actual Minecraft codecs. */
class NativeResourcePackMetadataTest {
    private JsonObject pack() throws Exception {
        try (var stream = getClass().getResourceAsStream("/pack.mcmeta")) {
            assertNotNull(stream, "The actual processed mod resource metadata must exist");
            var pack = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack");
            assertEquals("RenderScope client assets", pack.get("description").getAsString());
            return pack;
        }
    }

    private JsonObject actualPackVersions() throws Exception {
        try (var stream = getClass().getResourceAsStream("/version.json")) {
            assertNotNull(stream, "The selected Minecraft version descriptor must exist");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("pack_version");
        }
    }

    @Test void emittedNativeMetadataIsAcceptedByBothActualPackTypeCodecs() throws Exception {
        var pack = pack();
        var versions = actualPackVersions();
        int major = versions.get("data_major").getAsInt(), minor = versions.get("data_minor").getAsInt();
        for (var type : PackType.values()) {
            var decoded = PackFormat.packCodec(type).codec().parse(JsonOps.INSTANCE, pack);
            assertTrue(decoded.result().isPresent(), () -> type + " rejected the emitted metadata: " + decoded.error());
            assertEquals(new PackFormat(major, minor), decoded.result().orElseThrow().minInclusive());
            assertEquals(new PackFormat(major, minor), decoded.result().orElseThrow().maxInclusive());
        }
    }

    @Test void resourceRangeAlonePassesClientButFailsTheNativeServerPackRead() throws Exception {
        var pack = pack();
        var versions = actualPackVersions();
        var range = new com.google.gson.JsonArray();
        range.add(versions.get("resource_major").getAsInt());
        range.add(versions.get("resource_minor").getAsInt());
        pack.add("min_format", range); pack.add("max_format", range);
        assertTrue(PackFormat.packCodec(PackType.CLIENT_RESOURCES).codec().parse(JsonOps.INSTANCE, pack).result().isPresent());
        assertTrue(PackFormat.packCodec(PackType.SERVER_DATA).codec().parse(JsonOps.INSTANCE, pack).error().isPresent(),
                "The native loader reads both pack types before synthesizing hidden CLIENT metadata");
    }
}
