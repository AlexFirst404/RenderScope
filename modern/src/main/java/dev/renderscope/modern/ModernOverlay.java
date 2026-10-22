package dev.renderscope.modern;

import dev.renderscope.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Uses vanilla's backend-independent gizmo pipeline; no raw GL calls or object mutations. */
public final class ModernOverlay {
    private static final Map<InstanceKey, Long> SELECTED = new LinkedHashMap<>();
    private static final Set<InstanceKey> TRACKED = new LinkedHashSet<>();
    private static int queuedBoxes, queuedLabels;
    private static long hudDrawPasses, inspectorDrawPasses;
    private ModernOverlay() { }
    public static void clear() { SELECTED.clear(); TRACKED.clear(); queuedBoxes = queuedLabels = 0; hudDrawPasses = inspectorDrawPasses = 0; }
    public static int queuedBoxes() { return queuedBoxes; }
    public static int queuedLabels() { return queuedLabels; }
    public static long hudDrawPasses() { return hudDrawPasses; }
    public static long inspectorDrawPasses() { return inspectorDrawPasses; }
    public static boolean highlighted(InstanceKey key) { return SELECTED.containsKey(key); }
    public static boolean tracked(InstanceKey key) { return TRACKED.contains(key); }
    public static void toggleHighlight(InstanceKey key) {
        if (SELECTED.remove(key) != null) return;
        if (SELECTED.size() >= 100) SELECTED.remove(SELECTED.keySet().iterator().next());
        int timeout = ModernHooks.SETTINGS.highlightTimeoutSeconds;
        SELECTED.put(key, timeout == 0 ? Long.MAX_VALUE : System.nanoTime() + timeout * 1_000_000_000L);
    }
    public static void toggleTrack(InstanceKey key) {
        if (TRACKED.remove(key)) return;
        if (TRACKED.size() >= 100) TRACKED.remove(TRACKED.iterator().next());
        TRACKED.add(key);
    }
    public static void emitWorldGizmos() {
        queuedBoxes = queuedLabels = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        SELECTED.entrySet().removeIf(entry -> entry.getValue() < now);
        ProfilerSnapshot snapshot = ModernHooks.PROFILER.snapshot();
        var settings = ModernHooks.SETTINGS;
        List<InstanceMetric> visible = snapshot.instances().stream()
            .filter(instance -> instance.context().key().dimension().equals(mc.level.dimension().identifier().toString()))
            .filter(instance -> selected(instance.context().key()) || !instance.context().key().equals(ModernHooks.entityKey(mc.player)))
            .filter(instance -> selected(instance.context().key()) || settings.heatmap
                && (instance.context().key().kind() == ObjectKind.ENTITY ? settings.heatmapEntities : settings.heatmapBlockEntities)
                && instance.stats().averageMs() >= settings.minimumCostMs)
            .filter(instance -> selected(instance.context().key()) || distanceSquared(instance.context().position(), mc.player.position()) <= (double) settings.maximumDistance * settings.maximumDistance)
            .sorted(Comparator.comparing((InstanceMetric metric) -> !selected(metric.context().key()))
                .thenComparing(Comparator.comparingDouble((InstanceMetric metric) -> metric.stats().averageMs()).reversed()))
            .limit(settings.maxHighlightedObjects).toList();
        if (visible.isEmpty()) return;
        double[] costs = visible.stream().mapToDouble(metric -> metric.stats().averageMs()).sorted().toArray();
        double redThreshold = costs[Math.min(costs.length - 1, (int) Math.floor(costs.length * 0.95))];
        try (var ignored = ModernPlatform.collectGizmos()) {
            for (InstanceMetric metric : visible) {
                AABB box = liveBounds(metric);
                if (box == null) continue;
                boolean selected = selected(metric.context().key());
                int color = selected ? 0xff65d9ff : heatColor(metric.stats().averageMs(), redThreshold);
                GizmoProperties outline = Gizmos.cuboid(box.inflate(0.02), GizmoStyle.strokeAndFill(color, selected ? 3 : 2,
                    (color & 0xffffff) | 0x14000000));
                queuedBoxes++;
                if (settings.throughWalls) outline.setAlwaysOnTop();
                if (settings.showLabels) {
                    Vec3 center = box.getCenter();
                    label(String.format(Locale.ROOT, "%.3f ms CPU", metric.stats().averageMs()),
                        new Vec3(center.x, box.maxY + 0.6, center.z), color, settings.throughWalls);
                    label(metric.context().registryId(), new Vec3(center.x, box.maxY + 0.32, center.z), color, settings.throughWalls);
                }
            }
        }
    }
    private static void label(String text, Vec3 position, int color, boolean throughWalls) {
        GizmoProperties label = Gizmos.billboardText(text, position, TextGizmo.Style.forColorAndCentered(color).withScale(0.25f));
        queuedLabels++;
        if (throughWalls) label.setAlwaysOnTop();
    }
    public static AABB liveBounds(InstanceMetric metric) {
        Minecraft mc = Minecraft.getInstance();
        SampleContext context = metric.context();
        if (mc.level == null || !context.key().dimension().equals(mc.level.dimension().identifier().toString())) return null;
        if (context.key().kind() == ObjectKind.BLOCK_ENTITY) {
            Position p = context.position();
            BlockPos pos = BlockPos.containing(p.x(), p.y(), p.z());
            var entity = mc.level.getBlockEntity(pos);
            if (entity == null || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString().equals(context.registryId())) return null;
            return new AABB(pos);
        }
        String[] identity = context.key().identity().split("#", 2);
        if (identity.length != 2) return null;
        try {
            var entity = mc.level.getEntity(Integer.parseInt(identity[0]));
            if (entity == null || entity.isRemoved() || !entity.getUUID().toString().equals(identity[1])
                || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(context.registryId())) return null;
            return entity.getBoundingBox();
        } catch (NumberFormatException ignored) { return null; }
    }
    public static Optional<InstanceMetric> inspected() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return Optional.empty();
        InstanceKey key = null;
        if (mc.hitResult instanceof EntityHitResult hit) key = ModernHooks.entityKey(hit.getEntity());
        else if (mc.hitResult instanceof BlockHitResult hit) {
            var entity = mc.level.getBlockEntity(hit.getBlockPos());
            if (entity != null) key = ModernHooks.blockKey(entity);
        }
        if (key == null) return Optional.empty();
        InstanceKey selected = key;
        return ModernHooks.PROFILER.snapshot().instances().stream().filter(metric -> metric.context().key().equals(selected)).findFirst();
    }
    public static void extractHud(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        // RETURN also covers loader-specific early exits. Respect vanilla F1
        // before submitting any overlay primitives on those paths.
        if (mc.level == null || ModernPlatform.hudHidden(mc)) return;
        hudDrawPasses++;
        ProfilerSnapshot snapshot = ModernHooks.PROFILER.snapshot();
        if (snapshot.capture().active()) graphics.text(mc.font, "RenderScope Recording в—Џ", 8, 8, 0xffff6575);
        if (!ModernHooks.SETTINGS.inspect || ModernPlatform.currentScreen(mc) != null) return;
        inspected().ifPresent(metric -> {
            int y = snapshot.capture().active() ? 25 : 8;
            graphics.fill(6, y - 3, 284, y + 65, 0xcc101820);
            graphics.text(mc.font, "RenderScope Inspector В· CPU", 10, y, 0xff65d9ff);
            graphics.text(mc.font, metric.context().registryId(), 10, y + 12, 0xffffffff);
            graphics.text(mc.font, ModCatalog.displayName(metric.context().registryOwner()), 10, y + 24, 0xffb5c9d5);
            graphics.text(mc.font, String.format(Locale.ROOT, "Avg %.3f  P95 %.3f  Peak %.3f ms", metric.stats().averageMs(),
                metric.stats().p95Ms(), metric.stats().peakMs()), 10, y + 36, 0xffffffff);
            graphics.text(mc.font, "Calls " + metric.stats().calls() + " В· extract + submit", 10, y + 48, 0xffb5c9d5);
            // Counts actual submitted HUD primitives, not a data-only hit test.
            inspectorDrawPasses++;
        });
    }
    private static boolean selected(InstanceKey key) { return SELECTED.containsKey(key) || TRACKED.contains(key); }
    private static double distanceSquared(Position position, Vec3 point) {
        double x = position.x() - point.x, y = position.y() - point.y, z = position.z() - point.z;
        return x*x + y*y + z*z;
    }
    private static int heatColor(double cost, double threshold) {
        double t = threshold > 0 ? Math.min(1, cost / threshold) : 0;
        int r = (int) (t < 0.5 ? 70 + 370*t : 255);
        int g = (int) (t < 0.5 ? 230 : 230 - 360*(t-0.5));
        return 0xff000000 | (Math.min(255, r) << 16) | (Math.max(45, g) << 8) | 65;
    }
}
