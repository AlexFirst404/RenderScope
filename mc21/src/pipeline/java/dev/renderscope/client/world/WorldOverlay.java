package dev.renderscope.client.world;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;
import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.client.ModOwnership;
import dev.renderscope.core.InstanceKey;
import dev.renderscope.core.InstanceMetric;
import dev.renderscope.core.ObjectKind;
import dev.renderscope.core.Position;
import dev.renderscope.core.ProfilerSnapshot;
import dev.renderscope.core.RenderProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Minecraft adapter for the value-only profiler. All rendering and selection runs on the client thread. */
public final class WorldOverlay {
    private static final int HIGHLIGHT_COLOR = 0x66DDFF;
    private static final int TRACK_COLOR = 0xDDAAFF;
    private static final float LABEL_SCALE = 0.025F;
    private final RenderProfiler profiler;
    private final ProfilerSettings settings;
    // This buffer belongs exclusively to RenderScope. Never flush the world's shared buffer source.
    private final MultiBufferSource.BufferSource buffers = MultiBufferSource.immediate(new ByteBufferBuilder(65536));
    private final Map<String, String> modNames = new LinkedHashMap<>();
    private InstanceKey highlighted;
    private InstanceKey tracked;
    private InstanceMetric highlightedValue;
    private InstanceMetric trackedValue;
    private long highlightUntilNanos;
    private String dimension;
    private int renderedWorldBoxes;
    private int renderedWorldLabels;
    private final Set<InstanceKey> renderedBoxKeys = new HashSet<>();
    private final Set<InstanceKey> renderedLabelKeys = new HashSet<>();

    public WorldOverlay(RenderProfiler profiler, ProfilerSettings settings) {
        this.profiler = profiler;
        this.settings = settings;
    }

    public void toggleHighlight(InstanceKey key) {
        if (isHighlighted(key)) {
            highlighted = null;
            highlightedValue = null;
            return;
        }
        highlighted = key;
        highlightedValue = find(profiler.snapshot(), key);
        highlightUntilNanos = settings.highlightTimeoutSeconds == 0 ? Long.MAX_VALUE
                : System.nanoTime() + settings.highlightTimeoutSeconds * 1_000_000_000L;
    }

    public void toggleTrack(InstanceKey key) {
        if (isTracked(key)) {
            tracked = null;
            trackedValue = null;
        } else {
            tracked = key;
            trackedValue = find(profiler.snapshot(), key);
        }
    }

    public boolean isHighlighted(InstanceKey key) {
        expireHighlight();
        return key != null && key.equals(highlighted);
    }

    public boolean isTracked(InstanceKey key) {
        return key != null && key.equals(tracked);
    }

    public void clear() {
        highlighted = null;
        tracked = null;
        highlightedValue = null;
        trackedValue = null;
        dimension = null;
        renderedWorldBoxes = 0;
        renderedWorldLabels = 0;
        renderedBoxKeys.clear();
        renderedLabelKeys.clear();
    }

    /** Last world-overlay pass, useful for integration diagnostics without retaining game objects. */
    public int renderedWorldBoxes() { return renderedWorldBoxes; }
    public int renderedWorldLabels() { return renderedWorldLabels; }
    public boolean renderedWorldBox(InstanceKey key) { return renderedBoxKeys.contains(key); }
    public boolean renderedWorldLabel(InstanceKey key) { return renderedLabelKeys.contains(key); }

    private void expireHighlight() {
        if (highlighted != null && System.nanoTime() >= highlightUntilNanos) {
            highlighted = null;
            highlightedValue = null;
        }
    }

    /** Called once with the actual world-view stack, after particles/translucent rendering. */
    public void render(PoseStack pose, Matrix4f projection, Vec3 camera, Frustum frustum, float partialTick) {
        if (dev.renderscope.client.integration.ShaderPasses.isShadowPass()) return;
        renderedWorldBoxes = 0;
        renderedWorldLabels = 0;
        renderedBoxKeys.clear();
        renderedLabelKeys.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (!checkWorld(minecraft)) return;
        expireHighlight();
        if (!settings.heatmap && highlighted == null && tracked == null) return;

        ProfilerSnapshot snapshot = profiler.snapshot();
        updateSelectionValues(snapshot);
        LinkedHashMap<InstanceKey, DrawTarget> targets = new LinkedHashMap<>();
        if (settings.heatmap) {
            double distanceSquared = settings.maximumDistance * (double) settings.maximumDistance;
            List<InstanceMetric> candidates = new ArrayList<>();
            for (InstanceMetric metric : snapshot.instances()) {
                InstanceKey key = metric.context().key();
                if (!key.dimension().equals(dimension) || metric.stats().averageMs() < settings.minimumCostMs) continue;
                if (key.kind() == ObjectKind.ENTITY && !settings.heatmapEntities) continue;
                if (key.kind() == ObjectKind.BLOCK_ENTITY && !settings.heatmapBlockEntities) continue;
                Position position = metric.context().position();
                if (camera.distanceToSqr(position.x(), position.y(), position.z()) <= distanceSquared) candidates.add(metric);
            }
            candidates.sort(Comparator.comparingDouble((InstanceMetric value) -> value.stats().averageMs()).reversed());
            int count = Math.min(settings.maxHighlightedObjects, candidates.size());
            for (int index = 0; index < count; index++) {
                InstanceMetric metric = candidates.get(index);
                double percentile = candidates.size() == 1 ? 1 : 1 - index / (double) (candidates.size() - 1);
                targets.put(metric.context().key(), new DrawTarget(metric, heatColor(percentile), false));
            }
        }
        if (highlightedValue != null) targets.put(highlighted, new DrawTarget(highlightedValue, HIGHLIGHT_COLOR, true));
        if (trackedValue != null) targets.put(tracked, new DrawTarget(trackedValue, TRACK_COLOR, true));
        if (targets.isEmpty()) return;

        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            RenderType lines = settings.throughWalls ? ScopeLines.THROUGH : ScopeLines.DEPTH;
            VertexConsumer consumer = buffers.getBuffer(lines);
            List<ResolvedTarget> labels = new ArrayList<>();
            for (DrawTarget target : targets.values()) {
                AABB box = resolveBox(minecraft, target.metric(), partialTick);
                if (box == null || (frustum != null && !frustum.isVisible(box))) continue;
                if (!target.selected() && box.getCenter().distanceToSqr(camera) > settings.maximumDistance * (double) settings.maximumDistance) continue;
                int color = target.color();
                ShapeRenderer.renderLineBox(pose, consumer, box.inflate(0.006),
                        ((color >> 16) & 255) / 255F, ((color >> 8) & 255) / 255F, (color & 255) / 255F, 0.95F);
                renderedWorldBoxes++;
                renderedBoxKeys.add(target.metric().context().key());
                if (settings.showLabels) labels.add(new ResolvedTarget(target, box));
            }
            buffers.endBatch();
            // Project the very same billboard transform used by Font, then reject overlapping labels.
            // Selected instances keep priority; other labels follow the scene's measured render cost.
            labels.sort(Comparator.comparing((ResolvedTarget value) -> !value.target().selected())
                    .thenComparing(Comparator.comparingDouble((ResolvedTarget value) -> value.target().metric().stats().averageMs()).reversed()));
            List<LabelRect> occupied = hudLabelExclusions(minecraft, snapshot);
            int labelsDrawn = 0;
            for (ResolvedTarget label : labels) {
                LabelPlacement placement = projectLabel(minecraft, pose, label, projection);
                if (placement == null || occupied.stream().anyMatch(rect -> rect.overlaps(placement.rect()))) continue;
                renderLabel(minecraft, pose, placement);
                renderedWorldLabels++;
                renderedLabelKeys.add(label.target().metric().context().key());
                occupied.add(placement.rect());
                if (++labelsDrawn >= 32) break;
            }
            buffers.endBatch();
        } finally {
            // Finish only our private batch. Each native render pipeline owns its GPU state.
            try {
                buffers.endBatch();
            } finally {
                pose.popPose();
            }
        }
    }

    private boolean checkWorld(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return false;
        }
        String current = minecraft.level.dimension().location().toString();
        if (dimension != null && !dimension.equals(current)) clear();
        dimension = current;
        return true;
    }

    private void updateSelectionValues(ProfilerSnapshot snapshot) {
        InstanceMetric currentHighlight = find(snapshot, highlighted);
        InstanceMetric currentTrack = find(snapshot, tracked);
        if (currentHighlight != null) highlightedValue = currentHighlight;
        if (currentTrack != null) trackedValue = currentTrack;
    }

    private static InstanceMetric find(ProfilerSnapshot snapshot, InstanceKey key) {
        if (key == null) return null;
        for (InstanceMetric metric : snapshot.instances()) if (metric.context().key().equals(key)) return metric;
        return null;
    }

    private AABB resolveBox(Minecraft minecraft, InstanceMetric metric, float partialTick) {
        InstanceKey key = metric.context().key();
        if (minecraft.level == null || !key.dimension().equals(dimension)) return null;
        if (key.kind() == ObjectKind.ENTITY) {
            Entity entity = resolveEntity(minecraft, key);
            if (entity == null || entity.isRemoved()
                    || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(metric.context().registryId())) return null;
            return entity.getBoundingBox().move(
                    Mth.lerp(partialTick, entity.xOld, entity.getX()) - entity.getX(),
                    Mth.lerp(partialTick, entity.yOld, entity.getY()) - entity.getY(),
                    Mth.lerp(partialTick, entity.zOld, entity.getZ()) - entity.getZ());
        }
        Position position = metric.context().position();
        BlockPos blockPos = BlockPos.containing(position.x(), position.y(), position.z());
        if (!minecraft.level.hasChunkAt(blockPos)) return null;
        BlockEntity blockEntity = minecraft.level.getBlockEntity(blockPos);
        if (blockEntity == null || blockEntity.isRemoved()
                || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()).toString().equals(metric.context().registryId())) return null;
        return new AABB(blockPos);
    }

    private static Entity resolveEntity(Minecraft minecraft, InstanceKey key) {
        if (minecraft.level == null) return null;
        try {
            return minecraft.level.getEntity(Integer.parseInt(key.identity()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void orientLabel(Minecraft minecraft, PoseStack pose, AABB box) {
        // Font's positive Y points down after billboard scaling. Lift the complete two-line
        // rectangle above the box so its registry ID is not depth-occluded by its own object.
        double height = labelBottom(minecraft) * LABEL_SCALE + 0.12;
        pose.translate((box.minX + box.maxX) * 0.5, box.maxY + height, (box.minZ + box.maxZ) * 0.5);
        pose.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
    }

    private static int labelLineSpacing(Minecraft minecraft) { return minecraft.font.lineHeight + 1; }
    private static int labelBottom(Minecraft minecraft) { return labelLineSpacing(minecraft) + minecraft.font.lineHeight + 2; }

    private LabelPlacement projectLabel(Minecraft minecraft, PoseStack pose, ResolvedTarget resolved, Matrix4f projection) {
        String timing = formatMs(resolved.target().metric().stats().averageMs()) + " CPU ms/frame";
        String name = resolved.target().metric().context().registryId();
        float halfWidth = Math.max(minecraft.font.width(timing), minecraft.font.width(name)) / 2F + 2;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        int screenWidth = minecraft.getWindow().getWidth();
        int screenHeight = minecraft.getWindow().getHeight();
        pose.pushPose();
        try {
            orientLabel(minecraft, pose, resolved.box());
            for (float x : new float[]{-halfWidth, halfWidth}) {
                for (float y : new float[]{-2, labelBottom(minecraft)}) {
                    Vector4f clip = new Vector4f(x, y, 0, 1).mul(pose.last().pose()).mul(projection);
                    if (clip.w <= 0 || !Float.isFinite(clip.w)) return null;
                    double screenX = (clip.x / clip.w * 0.5 + 0.5) * screenWidth;
                    double screenY = (0.5 - clip.y / clip.w * 0.5) * screenHeight;
                    if (!Double.isFinite(screenX) || !Double.isFinite(screenY)) return null;
                    minX = Math.min(minX, screenX);
                    minY = Math.min(minY, screenY);
                    maxX = Math.max(maxX, screenX);
                    maxY = Math.max(maxY, screenY);
                }
            }
        } finally {
            pose.popPose();
        }
        // A label is either fully readable or omitted; the box still marks its world object.
        if (minX < 0 || minY < 0 || maxX > screenWidth || maxY > screenHeight) return null;
        return new LabelPlacement(resolved, new LabelRect(minX - 4, minY - 4, maxX + 4, maxY + 4), timing, name);
    }

    private List<LabelRect> hudLabelExclusions(Minecraft minecraft, ProfilerSnapshot snapshot) {
        List<LabelRect> occupied = new ArrayList<>();
        if (minecraft.screen != null) return occupied;
        int guiWidth = minecraft.getWindow().getGuiScaledWidth();
        int guiHeight = minecraft.getWindow().getGuiScaledHeight();
        double xScale = minecraft.getWindow().getWidth() / (double) guiWidth;
        double yScale = minecraft.getWindow().getHeight() / (double) guiHeight;
        if (settings.inspect) {
            int width = Math.min(245, Math.max(150, guiWidth / 2 - 16));
            int x = guiWidth - width - 8;
            int y = Math.max(28, guiHeight / 2 - 38);
            occupied.add(new LabelRect(x * xScale, y * yScale, (x + width) * xScale, (y + 87) * yScale));
        }
        if (trackedValue != null) {
            int width = Math.min(290, Math.max(120, guiWidth - 16));
            int y = Math.max(28, guiHeight - 127);
            occupied.add(new LabelRect(8 * xScale, y * yScale, (8 + width) * xScale, (y + 65) * yScale));
        }
        if (snapshot.capture().active()) occupied.add(new LabelRect(5 * xScale, 5 * yScale, 235 * xScale, 21 * yScale));
        return occupied;
    }

    private void renderLabel(Minecraft minecraft, PoseStack pose, LabelPlacement placement) {
        ResolvedTarget resolved = placement.resolved();
        DrawTarget target = resolved.target();
        pose.pushPose();
        try {
            orientLabel(minecraft, pose, resolved.box());
            String timing = placement.timing();
            String name = placement.name();
            Font.DisplayMode mode = settings.throughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
            minecraft.font.drawInBatch(timing, -minecraft.font.width(timing) / 2F, 0, 0xFF000000 | target.color(), false,
                    pose.last().pose(), buffers, mode, 0x90000000, 15728880);
            minecraft.font.drawInBatch(name, -minecraft.font.width(name) / 2F, labelLineSpacing(minecraft), 0xFFFFFFFF, false,
                    pose.last().pose(), buffers, mode, 0x90000000, 15728880);
        } finally {
            pose.popPose();
        }
    }

    /** The platform invokes this once per HUD frame. */
    public void renderHud(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!checkWorld(minecraft) || minecraft.options.hideGui) return;
        ProfilerSnapshot snapshot = profiler.snapshot();
        updateSelectionValues(snapshot);
        if (snapshot.capture().active()) {
            String recording = "RenderScope Recording \u25cf  " + String.format(Locale.ROOT, "%.1f s", snapshot.capture().elapsedSeconds());
            graphics.fill(5, 5, 15 + minecraft.font.width(recording), 21, 0xB0201111);
            graphics.drawString(minecraft.font, recording, 10, 9, 0xFFFF7777);
        }
        if (settings.inspect && minecraft.screen == null) renderInspector(minecraft, graphics, snapshot);
        if (trackedValue != null && minecraft.screen == null) renderTracked(minecraft, graphics, snapshot);
    }

    private void renderInspector(Minecraft minecraft, GuiGraphics graphics, ProfilerSnapshot snapshot) {
        InstanceMetric metric = inspectedMetric(minecraft, snapshot);
        List<String> lines = new ArrayList<>();
        lines.add("RenderScope Inspector");
        if (metric == null) {
            if (!(minecraft.hitResult instanceof EntityHitResult) && !(minecraft.hitResult instanceof BlockHitResult)) return;
            if (minecraft.hitResult instanceof BlockHitResult hit
                    && minecraft.level.getBlockEntity(hit.getBlockPos()) == null) return;
            lines.add("No renderer samples in current window");
        } else {
            lines.add(metric.context().registryId());
            lines.add("Mod: " + modName(metric.context().registryOwner()));
            lines.add("CPU renderer time (wall)");
            lines.add("Avg: " + formatMs(metric.stats().averageMs()) + " ms/frame");
            lines.add("P95: " + formatMs(metric.stats().p95Ms()) + " ms   Peak: " + formatMs(metric.stats().peakMs()) + " ms");
            lines.add("Calls: " + metric.stats().calls());
        }
        int width = Math.min(245, Math.max(150, eventWidth(minecraft) / 2 - 16));
        int x = eventWidth(minecraft) - width - 8;
        int y = Math.max(28, minecraft.getWindow().getGuiScaledHeight() / 2 - 38);
        drawPanel(minecraft, graphics, lines, x, y, width, HIGHLIGHT_COLOR);
    }

    private InstanceMetric inspectedMetric(Minecraft minecraft, ProfilerSnapshot snapshot) {
        if (minecraft.hitResult instanceof EntityHitResult hit) {
            String identity = Integer.toString(hit.getEntity().getId());
            for (InstanceMetric metric : snapshot.instances()) {
                InstanceKey key = metric.context().key();
                if (key.kind() == ObjectKind.ENTITY && key.dimension().equals(dimension) && key.identity().equals(identity)) return metric;
            }
        } else if (minecraft.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            BlockEntity current = minecraft.level.getBlockEntity(pos);
            if (current == null || current.isRemoved()) return null;
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(current.getType());
            if (type == null) return null;
            String registryId = type.toString();
            for (InstanceMetric metric : snapshot.instances()) {
                InstanceKey key = metric.context().key();
                Position location = metric.context().position();
                if (key.kind() == ObjectKind.BLOCK_ENTITY && key.dimension().equals(dimension)
                        && registryId.equals(metric.context().registryId())
                        && BlockPos.containing(location.x(), location.y(), location.z()).equals(pos)) return metric;
            }
        }
        return null;
    }

    private void renderTracked(Minecraft minecraft, GuiGraphics graphics, ProfilerSnapshot snapshot) {
        AABB box = resolveBox(minecraft, trackedValue, 1);
        List<String> lines = new ArrayList<>();
        lines.add("RenderScope Tracking");
        lines.add(trackedValue.context().registryId());
        if (box == null) {
            lines.add("Object is outside loaded area or removed");
        } else {
            Vec3 position = box.getCenter();
            double distance = minecraft.player.position().distanceTo(position);
            lines.add(String.format(Locale.ROOT, "X %.1f  Y %.1f  Z %.1f  |  %.0f blocks", position.x, box.minY, position.z, distance));
            double bearing = Mth.wrapDegrees(Math.toDegrees(Math.atan2(position.z - minecraft.player.getZ(), position.x - minecraft.player.getX())) - 90 - minecraft.player.getYRot());
            lines.add(Math.abs(bearing) < 12 ? "Ahead" : bearing < 0 ? "Turn left" : "Turn right");
            InstanceMetric current = find(snapshot, tracked);
            lines.add(current == null ? "Not measured in current window"
                    : "CPU: " + formatMs(current.stats().averageMs()) + " ms/frame  |  Peak " + formatMs(current.stats().peakMs()) + " ms");
        }
        int width = Math.min(290, Math.max(120, eventWidth(minecraft) - 16));
        int y = minecraft.getWindow().getGuiScaledHeight() - 72 - lines.size() * 11;
        drawPanel(minecraft, graphics, lines, 8, Math.max(28, y), width, TRACK_COLOR);
    }

    private static int eventWidth(Minecraft minecraft) {
        return minecraft.getWindow().getGuiScaledWidth();
    }

    private static void drawPanel(Minecraft minecraft, GuiGraphics graphics, List<String> lines, int x, int y, int width, int accent) {
        int height = lines.size() * 11 + 10;
        graphics.fill(x, y, x + width, y + height, 0xD010141C);
        graphics.fill(x, y, x + 2, y + height, 0xFF000000 | accent);
        for (int index = 0; index < lines.size(); index++) {
            String line = minecraft.font.plainSubstrByWidth(lines.get(index), width - 12);
            graphics.drawString(minecraft.font, line, x + 6, y + 6 + index * 11, index == 0 ? 0xFF000000 | accent : 0xFFE4E8EE);
        }
    }

    private String modName(String id) {
        if (id.equals("minecraft")) return "Minecraft";
        return modNames.computeIfAbsent(id, ModOwnership::displayName);
    }

    private static String formatMs(double ms) {
        if (ms > 0 && ms < 0.001) return "<0.001";
        return String.format(Locale.ROOT, "%.3f", ms);
    }

    /** Percentile colors are relative to the current eligible scene, with the top five percent red. */
    private static int heatColor(double percentile) {
        if (percentile >= 0.95) return 0xFF4242;
        if (percentile < 0.5) {
            double ratio = percentile * 2;
            return ((int) (50 + 205 * ratio) << 16) | (220 << 8) | 65;
        }
        double ratio = (percentile - 0.5) / 0.45;
        return (255 << 16) | ((int) (220 - 154 * ratio) << 8) | 65;
    }

    private record DrawTarget(InstanceMetric metric, int color, boolean selected) {}
    private record ResolvedTarget(DrawTarget target, AABB box) {}
    private record LabelRect(double left, double top, double right, double bottom) {
        boolean overlaps(LabelRect other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
    }
    private record LabelPlacement(ResolvedTarget resolved, LabelRect rect, String timing, String name) {}

    /** Native pipelines set depth behavior at draw time, with no raw OpenGL state mutation. */
    private static final class ScopeLines {
        static final RenderType DEPTH = create(false);
        static final RenderType THROUGH = create(true);
        private static RenderType create(boolean through) {
            RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(ResourceLocation.fromNamespaceAndPath("renderscope", "pipeline/outlines_" + through))
                    .withDepthTestFunction(through ? DepthTestFunction.NO_DEPTH_TEST : DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false).withCull(false).build());
            dev.renderscope.client.integration.IrisLinePipelines.assign(pipeline);
            return RenderType.create("renderscope_outlines_" + through, 65536, pipeline,
                    RenderType.CompositeState.builder().createCompositeState(false));
        }
    }
}
