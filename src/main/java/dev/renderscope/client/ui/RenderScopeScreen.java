package dev.renderscope.client.ui;

import dev.renderscope.client.ProfilerSettings;
import dev.renderscope.core.AggregateMetric;
import dev.renderscope.core.CaptureReport;
import dev.renderscope.core.FramePoint;
import dev.renderscope.core.InstanceKey;
import dev.renderscope.core.InstanceMetric;
import dev.renderscope.core.ObjectKind;
import dev.renderscope.core.Performance;
import dev.renderscope.core.Position;
import dev.renderscope.core.ProfilerSnapshot;
import dev.renderscope.core.RenderProfiler;
import dev.renderscope.core.Statistics;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native Minecraft UI. All displayed metrics come from an immutable profiler snapshot. */
public final class RenderScopeScreen extends Screen {
    private static final int TEXT = 0xffe5e9ed;
    private static final int MUTED = 0xff9caab7;
    private static final int ACCENT = 0xffef5350;
    private static final int GREEN = 0xff77dd91;
    private static final int ROW_HEIGHT = 20;
    private static final ResourceLocation LOGO = new ResourceLocation("renderscope", "textures/gui/logo.png");
    private final Screen parent;
    private final RenderProfiler profiler;
    private final ScreenActions actions;
    private ProfilerSnapshot snapshot;
    private Tab tab = Tab.OVERVIEW;
    private EditBox search;
    private String query = "";
    private ObjectKind objectKind = ObjectKind.BLOCK_ENTITY;
    private String selectedMod;
    private AggregateMetric selectedObject;
    private InstanceMetric selectedInstance;
    private FramePoint selectedFrame;
    private int sortColumn = 1;
    private boolean sortAscending;
    private int settingsPage;
    private int graphMetric = 1;
    private double scroll;
    private int contentHeight;
    private int contentTop;
    private int contentBottom;
    private int tableTop;
    private int[] tableWidths = new int[0];
    private List<TableRow> tableRows = List.of();
    private List<String> tableHeaders = List.of();
    private Consumer<TableRow> rowAction;
    private Button captureButton;
    private Button exportButton;
    private String status = "";
    private long statusUntil;
    private List<Component> hoverText;
    private final List<Hit> hits = new ArrayList<>();

    public RenderScopeScreen(Screen parent, RenderProfiler profiler, ScreenActions actions) {
        super(Component.translatable("renderscope.title"));
        this.parent = parent;
        this.profiler = profiler;
        this.actions = actions;
        this.snapshot = profiler.snapshot();
    }

    private enum Tab { OVERVIEW, MODS, OBJECTS, TIMELINE, HEATMAP, CAPTURE, SETTINGS }

    private record TableRow(Object value, List<String> cells, List<Double> numbers) {}
    private record Hit(int x, int y, int width, int height, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    @Override
    protected void init() {
        snapshot = profiler.snapshot();
        contentTop = 64;
        contentBottom = height - 35;
        captureButton = null;
        exportButton = null;
        search = null;
        int tabWidth = Math.max(1, (width - 16) / 6);
        for (int i = 0; i < 6; i++) {
            Tab value = Tab.values()[i];
            Button button = button(8 + i * tabWidth, 36, i == 5 ? width - 8 - (8 + i * tabWidth) : tabWidth - 2,
                    tr("tab." + value.name().toLowerCase(Locale.ROOT)), () -> changeTab(value));
            button.active = tab != value;
        }
        captureButton = button(8, height - 27, Math.min(132, Math.max(92, width / 3)), captureLabel(), () -> {
            if (snapshot.capture().active()) actions.stopCapture();
            else actions.startCapture(actions.settings().captureDurationSeconds);
            snapshot = profiler.snapshot();
            rebuild();
        });
        button(width - 158, height - 27, 84, tr("tab.settings"), () -> changeTab(Tab.SETTINGS));
        button(width - 70, height - 27, 62, tr("done"), this::onClose);

        if (tab == Tab.MODS || tab == Tab.OBJECTS) {
            boolean detail = selectedMod != null || selectedObject != null || selectedInstance != null;
            if (detail) {
                button(8, contentTop, 66, tr("back"), this::back);
                if (selectedInstance != null) instanceButtons(contentTop);
            } else {
                int filterWidth = tab == Tab.OBJECTS ? Math.min(132, width / 3) : 0;
                search = new EditBox(font, 8, contentTop, width - 16 - filterWidth - (filterWidth == 0 ? 0 : 4), 20,
                        component("search"));
                search.setMaxLength(160);
                search.setHint(component("search"));
                search.setValue(query);
                search.setResponder(value -> { query = value; scroll = 0; });
                addRenderableWidget(search);
                if (filterWidth > 0) button(width - 8 - filterWidth, contentTop, filterWidth,
                        kindName(objectKind), () -> {
                            objectKind = objectKind == ObjectKind.ENTITY ? ObjectKind.BLOCK_ENTITY : ObjectKind.ENTITY;
                            scroll = 0;
                            rebuild();
                        });
            }
            contentTop += 26;
        } else if (tab == Tab.TIMELINE) {
            button(8, contentTop, Math.min(190, width - 16), graphLabel(), () -> {
                graphMetric = (graphMetric + 1) % 5;
                rebuild();
            });
            contentTop += 26;
        } else if (tab == Tab.HEATMAP) {
            makeHeatmapControls();
        } else if (tab == Tab.CAPTURE) {
            int half = (width - 20) / 2;
            button(8, contentTop, half, durationLabel(), () -> {
                ProfilerSettings settings = actions.settings();
                settings.captureDurationSeconds = next(settings.captureDurationSeconds, 5, 10, 30, 0);
                actions.saveSettings();
                rebuild();
            });
            exportButton = button(12 + half, contentTop, width - half - 20, tr("export"), () -> setStatus(actions.exportCapture()));
            exportButton.active = profiler.lastCapture() != null;
            contentTop += 26;
        } else if (tab == Tab.SETTINGS) {
            makeSettingsControls();
        }
    }

    private void makeHeatmapControls() {
        ProfilerSettings s = actions.settings();
        int half = (width - 20) / 2;
        settingButton(8, contentTop, half, () -> tr("heatmap_enable") + ": " + onOff(s.heatmap), () -> s.heatmap = !s.heatmap);
        settingButton(12 + half, contentTop, width - half - 20, () -> tr("inspect") + ": " + onOff(s.inspect), () -> s.inspect = !s.inspect);
        int y = contentTop + 26;
        settingButton(8, y, half, () -> tr("entities") + ": " + onOff(s.heatmapEntities), () -> s.heatmapEntities = !s.heatmapEntities);
        settingButton(12 + half, y, width - half - 20, () -> tr("block_entities") + ": " + onOff(s.heatmapBlockEntities), () -> s.heatmapBlockEntities = !s.heatmapBlockEntities);
        y += 24;
        settingButton(8, y, half, () -> tr("max_distance") + ": " + s.maximumDistance, () -> s.maximumDistance = next(s.maximumDistance, 16, 32, 64, 96, 128, 256));
        settingButton(12 + half, y, width - half - 20, () -> tr("max_objects") + ": " + s.maxHighlightedObjects, () -> s.maxHighlightedObjects = next(s.maxHighlightedObjects, 25, 50, 100, 200, 500));
        y += 24;
        settingButton(8, y, half, () -> tr("min_cost") + ": " + decimal(s.minimumCostMs) + " ms", () -> s.minimumCostMs = nextDouble(s.minimumCostMs, 0, .01, .05, .1, .25, .5, 1));
        settingButton(12 + half, y, width - half - 20, () -> tr("labels") + ": " + onOff(s.showLabels), () -> s.showLabels = !s.showLabels);
        y += 24;
        settingButton(8, y, half, () -> tr("through_walls") + ": " + onOff(s.throughWalls), () -> s.throughWalls = !s.throughWalls);
        contentTop = y + 27;
    }

    private void makeSettingsControls() {
        int third = (width - 16) / 3;
        String[] names = {"settings_profiling", "settings_world", "settings_reports"};
        for (int i = 0; i < 3; i++) {
            int page = i;
            Button b = button(8 + i * third, contentTop, i == 2 ? width - 8 - (8 + i * third) : third - 2,
                    tr(names[i]), () -> { settingsPage = page; rebuild(); });
            b.active = settingsPage != i;
        }
        int y = contentTop + 28;
        int half = (width - 20) / 2;
        ProfilerSettings s = actions.settings();
        if (settingsPage == 0) {
            settingButton(8, y, width - 16, () -> tr("settings_profiling") + ": " + onOff(s.profiling), () -> s.profiling = !s.profiling);
            y += 24;
            settingButton(8, y, width - 16, () -> tr("measurement_mode") + ": " + modeName(), () -> {
                s.mode = s.mode == ProfilerSettings.MeasurementMode.TIMING_ONLY
                        ? ProfilerSettings.MeasurementMode.FULL : ProfilerSettings.MeasurementMode.TIMING_ONLY;
            });
            y += 24;
            settingButton(8, y, half, () -> tr("sample_window") + ": " + s.sampleWindowSeconds + " s",
                    () -> s.sampleWindowSeconds = next(s.sampleWindowSeconds, 1, 2, 3, 5, 10, 15, 30));
            settingButton(12 + half, y, width - half - 20, () -> tr("thread_cpu") + ": " + onOff(s.cpuTiming), () -> s.cpuTiming = !s.cpuTiming);
            y += 24;
            settingButton(8, y, width - 16, () -> tr("highlight_timeout") + ": " + s.highlightTimeoutSeconds + " s",
                    () -> s.highlightTimeoutSeconds = next(s.highlightTimeoutSeconds, 5, 10, 15, 30, 60, 120));
            contentTop = y + 28;
        } else if (settingsPage == 1) {
            settingButton(8, y, width - 16, () -> tr("heatmap_enable") + ": " + onOff(s.heatmap), () -> s.heatmap = !s.heatmap);
            y += 24;
            settingButton(8, y, half, () -> tr("entities") + ": " + onOff(s.heatmapEntities), () -> s.heatmapEntities = !s.heatmapEntities);
            settingButton(12 + half, y, width - half - 20, () -> tr("block_entities") + ": " + onOff(s.heatmapBlockEntities), () -> s.heatmapBlockEntities = !s.heatmapBlockEntities);
            y += 24;
            settingButton(8, y, half, () -> tr("labels") + ": " + onOff(s.showLabels), () -> s.showLabels = !s.showLabels);
            settingButton(12 + half, y, width - half - 20, () -> tr("through_walls") + ": " + onOff(s.throughWalls), () -> s.throughWalls = !s.throughWalls);
            y += 24;
            button(8, y, width - 16, tr("tab.heatmap"), () -> changeTab(Tab.HEATMAP));
            contentTop = y + 28;
        } else {
            settingButton(8, y, width - 16, () -> tr("positions") + ": " + onOff(s.includeWorldPositions), () -> s.includeWorldPositions = !s.includeWorldPositions);
            y += 24;
            settingButton(8, y, width - 16, this::durationLabel, () -> s.captureDurationSeconds = next(s.captureDurationSeconds, 5, 10, 30, 0));
            y += 24;
            button(8, y, width - 16, tr("tab.capture"), () -> changeTab(Tab.CAPTURE));
            contentTop = y + 28;
        }
    }

    private void instanceButtons(int y) {
        int available = width - 86;
        int cell = available / 3;
        InstanceKey key = selectedInstance.context().key();
        button(78, y, cell - 2, tr("highlight") + (actions.isHighlighted(key) ? " ✓" : ""), () -> {
            actions.toggleHighlight(key);
            rebuild();
        });
        button(78 + cell, y, cell - 2, tr("track") + (actions.isTracked(key) ? " ✓" : ""), () -> {
            actions.toggleTrack(key);
            rebuild();
        });
        button(78 + 2 * cell, y, width - 8 - (78 + 2 * cell), tr("copy_position"), () -> {
            minecraft.keyboardHandler.setClipboard(position(selectedInstance.context().position()));
            setStatus(tr("copied"));
        });
    }

    @Override
    public void tick() {
        snapshot = profiler.snapshot();
        if (search != null) search.tick();
        if (captureButton != null) captureButton.setMessage(Component.literal(captureLabel()));
        if (exportButton != null) exportButton.active = profiler.lastCapture() != null;
        if (selectedInstance != null) {
            InstanceKey key = selectedInstance.context().key();
            snapshot.instances().stream().filter(value -> value.context().key().equals(key)).findFirst().ifPresent(value -> selectedInstance = value);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fillGradient(0, 0, width, height, 0xee12151a, 0xf21b2027);
        g.fill(0, 0, width, 2, ACCENT);
        hoverText = null;
        hits.clear();
        tableRows = List.of();
        rowAction = null;
        tableHeaders = List.of();
        drawHeader(g);
        g.fill(7, 62, width - 7, height - 33, 0x8820252d);
        g.enableScissor(8, contentTop, width - 8, contentBottom);
        contentHeight = 0;
        switch (tab) {
            case OVERVIEW -> renderOverview(g, mouseX, mouseY);
            case MODS -> { if (selectedMod == null) renderMods(g, mouseX, mouseY); else renderModDetail(g, mouseX, mouseY); }
            case OBJECTS -> {
                if (selectedInstance != null) renderInstanceDetail(g, mouseX, mouseY);
                else if (selectedObject != null) renderObjectDetail(g, mouseX, mouseY);
                else renderObjects(g, mouseX, mouseY);
            }
            case TIMELINE -> renderTimeline(g, mouseX, mouseY);
            case HEATMAP -> renderHeatmapText(g, mouseX, mouseY);
            case CAPTURE -> renderCapture(g, mouseX, mouseY);
            case SETTINGS -> renderSettingsText(g, mouseX, mouseY);
        }
        g.disableScissor();
        drawScrollbar(g);
        super.render(g, mouseX, mouseY, partialTick);
        if (!status.isEmpty() && System.nanoTime() < statusUntil) {
            int maxWidth = width - 24;
            String value = font.plainSubstrByWidth(status, maxWidth);
            int x = (width - font.width(value)) / 2;
            g.fill(x - 5, height - 47, x + font.width(value) + 5, height - 31, 0xef13171d);
            g.drawString(font, value, x, height - 43, GREEN);
            if (mouseY >= height - 48 && mouseY <= height - 31) hoverText = List.of(Component.literal(status));
        }
        if (hoverText != null && !hoverText.isEmpty()) g.renderComponentTooltip(font, hoverText, mouseX, mouseY);
    }

    private void drawHeader(GuiGraphics g) {
        g.blit(LOGO, 10, 5, 0, 0, 24, 24, 24, 24);
        g.drawString(font, title, 43, 11, TEXT);
        String state = snapshot.capture().active() ? tr("recording") : snapshot.enabled() ? tr("live") : tr("disabled");
        String performance = state + " ●   " + integer(snapshot.performance().fps()) + " FPS   " + ms(snapshot.performance().currentFrameMs());
        if (width >= 440) g.drawString(font, performance, Math.max(120, width - font.width(performance) - 10), 11,
                snapshot.capture().active() ? ACCENT : snapshot.enabled() ? GREEN : MUTED);
        else g.drawString(font, performance, 43, 23, snapshot.capture().active() ? ACCENT : GREEN);
    }

    private void renderOverview(GuiGraphics g, int mx, int my) {
        Performance p = snapshot.performance();
        int y = contentTop + 6 - (int) scroll;
        y = heading(g, tr("performance") + " · " + integer(snapshot.windowSeconds()) + " s", y);
        y = pair(g, tr("fps"), integer(p.fps()), tr("frame"), ms(p.currentFrameMs()), y, mx, my);
        y = pair(g, tr("average_frame"), ms(p.averageFrameMs()), tr("low_fps"), integer(p.onePercentLowFps()) + " FPS", y, mx, my);
        y = pair(g, "P50", ms(p.p50FrameMs()), "P95", ms(p.p95FrameMs()), y, mx, my);
        y = pair(g, "P99", ms(p.p99FrameMs()), tr("worst_frame"), ms(p.worstFrameMs()), y, mx, my);
        y += 8;
        y = heading(g, tr("scene"), y);
        y = pair(g, tr("entities"), decimal(p.renderedEntitiesPerFrame()), tr("block_entities"), decimal(p.renderedBlockEntitiesPerFrame()), y, mx, my);
        y = pair(g, tr("calls"), decimal(p.rendererCallsPerFrame()) + "/f", tr("measured_objects"), integer(p.measuredObjects()), y, mx, my);
        y = pair(g, tr("vertices"), geometry(p.verticesPerFrame()), tr("vertex_data"), geometryBytes(p.estimatedBytesPerFrame()), y, mx, my);
        y += 8;
        y = heading(g, tr("offenders"), y);
        if (snapshot.mods().isEmpty()) y = line(g, tr(minecraft.level == null ? "no_world" : "no_data"), y, MUTED, mx, my);
        else {
            for (AggregateMetric mod : snapshot.mods().stream().limit(8).toList()) {
                int rowY = y;
                if (hovered(mx, my, 12, y, width - 24, 18)) g.fill(10, y - 3, width - 10, y + 15, 0x553e4e5c);
                drawTruncated(g, actions.modDisplayName(mod.id()), 14, y, width * 2 / 3 - 20, TEXT, mx, my);
                g.drawString(font, ms(mod.stats().averageMs()), width * 2 / 3, y, ACCENT);
                hits.add(new Hit(10, rowY - 3, width - 20, 18, () -> { tab = Tab.MODS; selectedMod = mod.id(); scroll = 0; rebuild(); }));
                y += 19;
            }
        }
        y += 7;
        y = line(g, tr("cpu_note"), y, MUTED, mx, my);
        y = line(g, tr("geometry_note"), y, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private void renderMods(GuiGraphics g, int mx, int my) {
        List<TableRow> rows = new ArrayList<>();
        double total = snapshot.performance().measuredRenderMs();
        Map<String, long[]> objectCounts = new HashMap<>();
        for (InstanceMetric instance : snapshot.instances()) {
            long[] counts = objectCounts.computeIfAbsent(instance.context().registryOwner(), ignored -> new long[2]);
            counts[instance.context().key().kind() == ObjectKind.ENTITY ? 0 : 1]++;
        }
        for (AggregateMetric mod : snapshot.mods()) {
            String name = actions.modDisplayName(mod.id());
            if (!matches(name, mod.id())) continue;
            Statistics s = mod.stats();
            long[] counts = objectCounts.getOrDefault(mod.id(), new long[2]);
            double entities = counts[0];
            double blocks = counts[1];
            double percent = total > 0 ? s.averageMs() * 100 / total : 0;
            rows.add(new TableRow(mod, List.of(name, decimal(s.averageMs()), decimal(percent), compact(s.callsPerFrame()), compact(entities), compact(blocks), geometry(s.verticesPerFrame())),
                    List.of(0.0, s.averageMs(), percent, s.callsPerFrame(), entities, blocks, s.verticesPerFrame())));
        }
        table(g, rows, List.of(tr("mod"), tr("render_ms"), tr("render_percent"), tr("calls_per_frame"), tr("entities"), tr("block_entities"), tr("vertices")),
                new int[]{30, 13, 11, 12, 10, 12, 12}, mx, my, row -> {
                    selectedMod = ((AggregateMetric) row.value()).id(); scroll = 0; rebuild();
                });
    }

    private void renderObjects(GuiGraphics g, int mx, int my) {
        List<TableRow> rows = new ArrayList<>();
        for (AggregateMetric object : snapshot.objects()) {
            if (object.kind() != objectKind || !matches(object.id(), actions.modDisplayName(object.registryOwner()))) continue;
            Statistics s = object.stats();
            rows.add(new TableRow(object, List.of(object.id(), actions.modDisplayName(object.registryOwner()), decimal(s.averageMs()), decimal(s.p95Ms()), decimal(s.peakMs()), compact(s.callsPerFrame()), integer(s.instances())),
                    List.of(0.0, 0.0, s.averageMs(), s.p95Ms(), s.peakMs(), s.callsPerFrame(), (double) s.instances())));
        }
        table(g, rows, List.of(tr("object"), tr("mod"), tr("average"), tr("p95"), tr("peak"), tr("calls_per_frame"), tr("instances")),
                new int[]{29, 18, 11, 10, 10, 11, 11}, mx, my, row -> {
                    selectedObject = (AggregateMetric) row.value(); scroll = 0; rebuild();
                });
    }

    private void table(GuiGraphics g, List<TableRow> rows, List<String> headers, int[] weights, int mx, int my, Consumer<TableRow> action) {
        int column = Math.min(sortColumn, headers.size() - 1);
        Comparator<TableRow> comparator = column == 0 || (tab == Tab.OBJECTS && column == 1)
                ? Comparator.comparing(row -> row.cells().get(column).toLowerCase(Locale.ROOT))
                : Comparator.comparingDouble(row -> row.numbers().get(column));
        if (!sortAscending) comparator = comparator.reversed();
        rows.sort(comparator);
        tableRows = rows;
        tableHeaders = headers;
        rowAction = action;
        tableWidths = columnWidths(weights);
        tableTop = contentTop;
        g.fill(8, tableTop, width - 8, tableTop + ROW_HEIGHT, 0xee303a46);
        int x = 10;
        for (int i = 0; i < headers.size(); i++) {
            String label = headers.get(i) + (i == sortColumn ? sortAscending ? " ↑" : " ↓" : "");
            drawTruncated(g, label, x + 2, tableTop + 6, tableWidths[i] - 6, i == sortColumn ? 0xffffb3ad : TEXT, mx, my);
            x += tableWidths[i];
        }
        int rowsTop = tableTop + ROW_HEIGHT;
        g.enableScissor(8, rowsTop, width - 8, contentBottom);
        int y = rowsTop - (int) scroll;
        for (int index = 0; index < rows.size(); index++) {
            TableRow row = rows.get(index);
            if (y + ROW_HEIGHT > rowsTop && y < contentBottom) {
                boolean over = hovered(mx, my, 8, Math.max(y, rowsTop), width - 16, Math.min(y + ROW_HEIGHT, contentBottom) - Math.max(y, rowsTop));
                g.fill(8, y, width - 8, y + ROW_HEIGHT, over ? 0xaa425262 : index % 2 == 0 ? 0x45323b45 : 0x20252d36);
                x = 10;
                for (int i = 0; i < headers.size(); i++) {
                    drawTruncated(g, row.cells().get(i), x + 2, y + 6, tableWidths[i] - 6, i == 1 && tab == Tab.MODS || i == 2 && tab == Tab.OBJECTS ? 0xffffb3ad : TEXT, mx, my);
                    x += tableWidths[i];
                }
                if (over) {
                    List<Component> tooltip = new ArrayList<>();
                    for (int i = 0; i < headers.size(); i++) tooltip.add(Component.literal(headers.get(i) + ": " + row.cells().get(i)));
                    hoverText = tooltip;
                }
            }
            y += ROW_HEIGHT;
        }
        if (rows.isEmpty()) drawTruncated(g, tr(query.isEmpty() ? minecraft.level == null ? "no_world" : "no_data" : "no_matches"), 14, rowsTop + 12, width - 28, MUTED, mx, my);
        g.disableScissor();
        contentHeight = ROW_HEIGHT + Math.max(ROW_HEIGHT, rows.size() * ROW_HEIGHT);
    }

    private void renderModDetail(GuiGraphics g, int mx, int my) {
        AggregateMetric mod = snapshot.mods().stream().filter(value -> value.id().equals(selectedMod)).findFirst().orElse(null);
        int y = contentTop + 5 - (int) scroll;
        y = heading(g, actions.modDisplayName(selectedMod), y);
        if (mod != null) y = statistics(g, mod.stats(), y, mx, my);
        AggregateMetric renderer = snapshot.renderers().stream().filter(value -> value.registryOwner().equals(selectedMod)).findFirst().orElse(null);
        y = line(g, tr("worst_renderer") + ": " + (renderer == null ? "—" : renderer.id()), y + 4, TEXT, mx, my);
        y = heading(g, tr("tab.objects"), y + 10);
        for (AggregateMetric object : snapshot.objects()) {
            if (!object.registryOwner().equals(selectedMod)) continue;
            int lineY = y;
            if (hovered(mx, my, 10, y - 2, width - 20, 18)) g.fill(10, y - 3, width - 10, y + 15, 0x66405060);
            drawTruncated(g, kindName(object.kind()) + " · " + object.id(), 14, y, width * 3 / 4 - 20, TEXT, mx, my);
            drawTruncated(g, ms(object.stats().averageMs()), width * 3 / 4, y, width / 4 - 15, 0xffffb3ad, mx, my);
            hits.add(new Hit(10, lineY - 2, width - 20, 18, () -> { tab = Tab.OBJECTS; selectedMod = null; selectedObject = object; selectedInstance = null; scroll = 0; rebuild(); }));
            y += 19;
        }
        y = line(g, tr("cpu_note"), y + 8, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private void renderObjectDetail(GuiGraphics g, int mx, int my) {
        AggregateMetric object = snapshot.objects().stream().filter(value -> value.id().equals(selectedObject.id()) && value.kind() == selectedObject.kind()).findFirst().orElse(selectedObject);
        int y = contentTop + 5 - (int) scroll;
        y = heading(g, object.id(), y);
        y = statistics(g, object.stats(), y, mx, my);
        y = line(g, tr("registry_owner") + ": " + actions.modDisplayName(object.registryOwner()), y + 3, TEXT, mx, my);
        y = line(g, tr("renderer") + ": " + object.rendererClass(), y, TEXT, mx, my);
        y = line(g, tr("renderer_owner") + ": " + actions.modDisplayName(object.rendererOwner()), y, MUTED, mx, my);
        y = line(g, tr("render_types") + ": " + renderTypes(object.stats()), y, MUTED, mx, my);
        y = heading(g, tr("instances"), y + 9);
        y = line(g, tr("instance_help"), y, MUTED, mx, my);
        int[] widths = columnWidths(new int[]{18, 18, 22, 21, 21});
        int x = 10;
        String[] headers = {"X", "Y", "Z", tr("average"), tr("peak")};
        g.fill(8, y - 3, width - 8, y + 14, 0xff303a46);
        for (int i = 0; i < headers.length; i++) {
            drawTruncated(g, headers[i], x + 3, y, widths[i] - 6, TEXT, mx, my);
            x += widths[i];
        }
        y += 18;
        int index = 0;
        for (InstanceMetric instance : snapshot.instances()) {
            if (!instance.context().registryId().equals(object.id()) || instance.context().key().kind() != object.kind()) continue;
            int rowY = y;
            Position p = instance.context().position();
            String[] values = {coordinate(p.x()), coordinate(p.y()), coordinate(p.z()), decimal(instance.stats().averageMs()), decimal(instance.stats().peakMs())};
            boolean over = hovered(mx, my, 8, y - 3, width - 16, ROW_HEIGHT);
            g.fill(8, y - 3, width - 8, y + 17, over ? 0xaa425262 : index++ % 2 == 0 ? 0x45323b45 : 0x20252d36);
            x = 10;
            for (int i = 0; i < values.length; i++) {
                drawTruncated(g, values[i], x + 3, y + 3, widths[i] - 6, TEXT, mx, my);
                x += widths[i];
            }
            hits.add(new Hit(8, rowY - 3, width - 16, ROW_HEIGHT, () -> { selectedInstance = instance; scroll = 0; rebuild(); }));
            y += ROW_HEIGHT;
        }
        contentHeight = y + (int) scroll - contentTop + 4;
    }

    private void renderInstanceDetail(GuiGraphics g, int mx, int my) {
        int y = contentTop + 5 - (int) scroll;
        y = heading(g, selectedInstance.context().registryId(), y);
        y = line(g, tr("position") + ": " + position(selectedInstance.context().position()), y, 0xffffb3ad, mx, my);
        y = line(g, tr("dimension") + ": " + selectedInstance.context().key().dimension(), y, MUTED, mx, my);
        y = statistics(g, selectedInstance.stats(), y + 7, mx, my);
        y = line(g, tr("registry_owner") + ": " + actions.modDisplayName(selectedInstance.context().registryOwner()), y + 5, TEXT, mx, my);
        y = line(g, tr("renderer") + ": " + selectedInstance.context().rendererClass(), y, TEXT, mx, my);
        y = line(g, tr("renderer_owner") + ": " + actions.modDisplayName(selectedInstance.context().rendererOwner()), y, TEXT, mx, my);
        y = line(g, tr("render_types") + ": " + renderTypes(selectedInstance.stats()), y, MUTED, mx, my);
        y = line(g, tr("cpu_note"), y + 8, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private int statistics(GuiGraphics g, Statistics s, int y, int mx, int my) {
        y = pair(g, tr("average"), ms(s.averageMs()) + "/f", tr("p95"), ms(s.p95Ms()), y, mx, my);
        y = pair(g, tr("peak"), ms(s.peakMs()), tr("calls"), decimal(s.callsPerFrame()) + "/f", y, mx, my);
        y = pair(g, tr("rolling_calls"), integer(s.calls()), tr("instances"), integer(s.instances()), y, mx, my);
        y = pair(g, tr("vertices"), geometry(s.verticesPerFrame()), tr("vertex_data"), geometryBytes(s.estimatedBytesPerFrame()), y, mx, my);
        y = pair(g, tr("failures"), integer(s.failures()), tr("thread_cpu"), actions.settings().cpuTiming && s.cpuMs() >= 0 ? ms(s.cpuMs()) : "—", y, mx, my);
        return y;
    }

    private void renderTimeline(GuiGraphics g, int mx, int my) {
        int graphTop = contentTop + 1;
        int graphBottom = graphTop + Math.max(54, Math.min(140, (contentBottom - contentTop) / 2));
        int left = 38;
        int right = width - 15;
        List<FramePoint> points = snapshot.timeline();
        double max = points.stream().mapToDouble(this::graphValue).max().orElse(1);
        max = Math.max(graphMetric == 0 ? 60 : 1, max * 1.1);
        g.fill(left, graphTop, right + 1, graphBottom, 0xcc12171e);
        for (int i = 0; i < 4; i++) {
            int y = graphTop + i * (graphBottom - graphTop) / 3;
            g.hLine(left, right, y, 0x44485665);
            g.drawString(font, compact(max * (3 - i) / 3), 10, y - 3, MUTED);
        }
        long latest = points.isEmpty() ? System.nanoTime() : points.get(points.size() - 1).timestampNanos();
        long earliest = latest - 30_000_000_000L;
        int previousX = -1;
        int previousY = -1;
        for (FramePoint point : points) {
            int x = left + (int) ((point.timestampNanos() - earliest) * (double) (right - left) / 30_000_000_000L);
            int y = graphBottom - 1 - (int) (Math.min(max, graphValue(point)) / max * (graphBottom - graphTop - 2));
            if (point.spike()) g.vLine(x, graphTop, graphTop + 7, ACCENT);
            if (previousX >= 0) plotLine(g, previousX, previousY, x, y, GREEN);
            previousX = x;
            previousY = y;
        }
        if (selectedFrame != null && selectedFrame.timestampNanos() >= earliest) {
            int x = left + (int) ((selectedFrame.timestampNanos() - earliest) * (double) (right - left) / 30_000_000_000L);
            g.vLine(x, graphTop, graphBottom, 0xfff5cc71);
        }
        g.drawString(font, "−30 s", left, graphBottom + 4, MUTED);
        g.drawString(font, "0 s", right - 18, graphBottom + 4, MUTED);
        final long first = earliest;
        hits.add(new Hit(left, graphTop, right - left, graphBottom - graphTop, () -> {
            if (!points.isEmpty()) {
                long target = first + (long) ((mx - left) * 30_000_000_000.0 / (right - left));
                selectedFrame = points.stream().min(Comparator.comparingLong(p -> Math.abs(p.timestampNanos() - target))).orElse(null);
                scroll = 0;
            }
        }));
        int detailTop = graphBottom + 20;
        g.enableScissor(8, detailTop, width - 8, contentBottom);
        int y = detailTop - (int) scroll;
        if (selectedFrame == null) {
            y = line(g, tr("timeline_help"), y + 5, MUTED, mx, my);
            if (points.isEmpty()) y = line(g, tr(minecraft.level == null ? "no_world" : "no_data"), y, MUTED, mx, my);
        } else {
            y = heading(g, tr(selectedFrame.spike() ? "spike" : "selected_frame") + " · " + ms(selectedFrame.frameMs()), y + 4);
            y = pair(g, tr("timeline_render"), ms(selectedFrame.measuredRenderMs()), tr("fps"), integer(selectedFrame.frameMs() > 0 ? 1000 / selectedFrame.frameMs() : 0), y, mx, my);
            y = pair(g, tr("timeline_entity"), ms(selectedFrame.entityRenderMs()), tr("timeline_block"), ms(selectedFrame.blockEntityRenderMs()), y, mx, my);
            y = heading(g, tr("offenders"), y + 6);
            for (Map.Entry<String, Double> entry : selectedFrame.topModMs().entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList())
                y = line(g, actions.modDisplayName(entry.getKey()) + " · " + ms(entry.getValue()), y, TEXT, mx, my);
            if (selectedFrame.worstInstance() != null) {
                InstanceMetric worst = selectedFrame.worstInstance();
                y = heading(g, tr("worst_instance"), y + 6);
                int clickY = y;
                y = line(g, worst.context().registryId() + " · " + position(worst.context().position()), y, 0xffffb3ad, mx, my);
                hits.add(new Hit(8, clickY - 2, width - 16, 16, () -> {
                    tab = Tab.OBJECTS;
                    selectedInstance = worst;
                    selectedMod = null;
                    selectedObject = null;
                    scroll = 0;
                    rebuild();
                }));
            }
        }
        g.disableScissor();
        contentHeight = y + (int) scroll - detailTop + (detailTop - contentTop);
    }

    private void renderCapture(GuiGraphics g, int mx, int my) {
        int y = contentTop + 5 - (int) scroll;
        if (snapshot.capture().active()) {
            y = heading(g, tr("recording") + " ● " + decimal(snapshot.capture().elapsedSeconds()) + " s", y);
            int duration = snapshot.capture().durationSeconds();
            if (duration > 0) {
                g.fill(12, y, width - 12, y + 6, 0xff303945);
                g.fill(12, y, 12 + (int) ((width - 24) * Math.min(1, snapshot.capture().elapsedSeconds() / duration)), y + 6, ACCENT);
                y += 15;
            }
        }
        CaptureReport report = profiler.lastCapture();
        if (report == null) {
            y = line(g, tr("capture_help"), y, TEXT, mx, my);
            y = line(g, tr("capture_empty"), y, MUTED, mx, my);
        } else {
            Performance p = report.performance();
            y = heading(g, tr("tab.capture"), y);
            y = pair(g, tr("capture_duration"), decimal(report.durationSeconds()) + " s", tr("capture_frames"), integer(report.frames()), y, mx, my);
            y = pair(g, tr("fps"), integer(p.fps()), tr("low_fps"), integer(p.onePercentLowFps()), y, mx, my);
            y = pair(g, tr("average_frame"), ms(p.averageFrameMs()), tr("worst_frame"), ms(p.worstFrameMs()), y, mx, my);
            y = pair(g, "P95", ms(p.p95FrameMs()), "P99", ms(p.p99FrameMs()), y, mx, my);
            if (!report.mods().isEmpty()) y = line(g, tr("capture_top_mod") + ": " + actions.modDisplayName(report.mods().get(0).id()) + " · " + ms(report.mods().get(0).stats().averageMs()), y + 6, TEXT, mx, my);
            if (!report.renderers().isEmpty()) y = line(g, tr("capture_top_renderer") + ": " + report.renderers().get(0).id(), y, TEXT, mx, my);
            if (!report.instances().isEmpty()) {
                InstanceMetric worst = report.instances().get(0);
                y = line(g, tr("worst_instance") + ": " + worst.context().registryId(), y, 0xffffb3ad, mx, my);
                y = line(g, position(worst.context().position()), y, TEXT, mx, my);
                int hitY = y - 28;
                hits.add(new Hit(8, hitY, width - 16, 28, () -> { tab = Tab.OBJECTS; selectedInstance = worst; selectedObject = null; selectedMod = null; scroll = 0; rebuild(); }));
            }
            y += 6;
            y = heading(g, tr("offenders"), y);
            for (AggregateMetric mod : report.mods().stream().limit(10).toList()) y = line(g, actions.modDisplayName(mod.id()) + " · " + ms(mod.stats().averageMs()) + "/f", y, TEXT, mx, my);
        }
        y = line(g, tr(actions.settings().includeWorldPositions ? "positions_on" : "positions_off"), y + 10, actions.settings().includeWorldPositions ? 0xffffcf80 : GREEN, mx, my);
        y = line(g, tr("capture_privacy"), y, MUTED, mx, my);
        y = line(g, tr("cpu_note"), y, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private void renderHeatmapText(GuiGraphics g, int mx, int my) {
        int y = contentTop + 3 - (int) scroll;
        y = line(g, tr("heatmap_help"), y, TEXT, mx, my);
        y = line(g, tr("heatmap_colors"), y, MUTED, mx, my);
        y = line(g, tr("cpu_note"), y, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private void renderSettingsText(GuiGraphics g, int mx, int my) {
        int y = contentTop + 3 - (int) scroll;
        y = line(g, tr(settingsPage == 2 ? "settings_positions_note" : settingsPage == 1 ? "heatmap_help" : "settings_mode_note"), y, MUTED, mx, my);
        contentHeight = y + (int) scroll - contentTop;
    }

    private int heading(GuiGraphics g, String value, int y) {
        g.drawString(font, font.plainSubstrByWidth(value, width - 26), 13, y, 0xffffb3ad);
        return y + 17;
    }

    private int line(GuiGraphics g, String value, int y, int color, int mx, int my) {
        drawTruncated(g, value, 13, y, width - 26, color, mx, my);
        return y + 14;
    }

    private int pair(GuiGraphics g, String leftLabel, String leftValue, String rightLabel, String rightValue, int y, int mx, int my) {
        int half = (width - 24) / 2;
        drawTruncated(g, leftLabel + ": " + leftValue, 13, y, half - 5, TEXT, mx, my);
        drawTruncated(g, rightLabel + ": " + rightValue, 13 + half, y, half - 5, TEXT, mx, my);
        return y + 15;
    }

    private void drawTruncated(GuiGraphics g, String value, int x, int y, int available, int color, int mx, int my) {
        if (available <= 0) return;
        String text = value;
        if (font.width(value) > available) text = font.plainSubstrByWidth(value, Math.max(0, available - font.width("…"))) + "…";
        g.drawString(font, text, x, y, color);
        if (!text.equals(value) && y >= contentTop - 9 && y < contentBottom && hovered(mx, my, x, y - 2, available, 12)) hoverText = List.of(Component.literal(value));
    }

    private void drawScrollbar(GuiGraphics g) {
        int visible = contentBottom - contentTop;
        if (contentHeight <= visible || visible <= 0) { scroll = 0; return; }
        scroll = Math.min(scroll, Math.max(0, contentHeight - visible));
        int thumb = Math.max(14, visible * visible / contentHeight);
        int y = contentTop + (int) (scroll / (contentHeight - visible) * (visible - thumb));
        g.fill(width - 5, contentTop, width - 2, contentBottom, 0x55303a46);
        g.fill(width - 5, y, width - 2, y + thumb, 0xff8493a2);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseY < contentTop || mouseY >= contentBottom) return false;
        if (!tableHeaders.isEmpty()) {
            if (mouseY >= tableTop && mouseY < tableTop + ROW_HEIGHT) {
                int x = 10;
                for (int i = 0; i < tableWidths.length; i++) {
                    if (mouseX >= x && mouseX < x + tableWidths[i]) {
                        if (sortColumn == i) sortAscending = !sortAscending;
                        else { sortColumn = i; sortAscending = i == 0 || tab == Tab.OBJECTS && i == 1; }
                        scroll = 0;
                        return true;
                    }
                    x += tableWidths[i];
                }
            } else if (mouseX >= 8 && mouseX < width - 8 && rowAction != null) {
                int index = (int) (mouseY - tableTop - ROW_HEIGHT + scroll) / ROW_HEIGHT;
                if (index >= 0 && index < tableRows.size()) { rowAction.accept(tableRows.get(index)); return true; }
            }
        }
        for (Hit hit : hits) if (hit.contains(mouseX, mouseY)) { hit.action().run(); return true; }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= 62 && mouseY < contentBottom && contentHeight > contentBottom - contentTop) {
            scroll = Math.max(0, Math.min(contentHeight - (contentBottom - contentTop), scroll - delta * ROW_HEIGHT * 2));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && (selectedMod != null || selectedObject != null || selectedInstance != null)) { back(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void back() {
        if (selectedInstance != null) selectedInstance = null;
        else if (selectedObject != null) selectedObject = null;
        else selectedMod = null;
        scroll = 0;
        rebuild();
    }

    private void changeTab(Tab value) {
        tab = value;
        selectedMod = null;
        selectedObject = null;
        selectedInstance = null;
        sortColumn = value == Tab.OBJECTS ? 2 : 1;
        sortAscending = false;
        query = "";
        scroll = 0;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private Button button(int x, int y, int buttonWidth, String label, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(x, y, buttonWidth, 20).build());
    }

    private void settingButton(int x, int y, int buttonWidth, Supplier<String> label, Runnable mutation) {
        button(x, y, buttonWidth, label.get(), () -> {
            mutation.run();
            actions.saveSettings();
            rebuild();
        });
    }

    private int[] columnWidths(int[] weights) {
        int[] values = new int[weights.length];
        int total = width - 20;
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            values[i] = i == weights.length - 1 ? total - assigned : total * weights[i] / 100;
            assigned += values[i];
        }
        return values;
    }

    private boolean matches(String... values) {
        if (query.isBlank()) return true;
        String filter = query.toLowerCase(Locale.ROOT);
        for (String value : values) if (value.toLowerCase(Locale.ROOT).contains(filter)) return true;
        return false;
    }

    private String captureLabel() { return tr(snapshot.capture().active() ? "stop_capture" : "start_capture"); }
    private String durationLabel() { return tr("duration") + ": " + (actions.settings().captureDurationSeconds == 0 ? tr("manual") : actions.settings().captureDurationSeconds + " s"); }
    private String modeName() { return tr(actions.settings().mode == ProfilerSettings.MeasurementMode.FULL ? "full" : "timing_only"); }
    private String geometry(double value) { return actions.settings().mode == ProfilerSettings.MeasurementMode.FULL ? compact(value) : "—"; }
    private String geometryBytes(double value) { return actions.settings().mode == ProfilerSettings.MeasurementMode.FULL ? bytes(value) : "—"; }
    private String kindName(ObjectKind kind) { return tr(kind == ObjectKind.ENTITY ? "entities" : "block_entities"); }
    private String graphLabel() { return tr(new String[]{"fps", "timeline_frame", "timeline_render", "timeline_entity", "timeline_block"}[graphMetric]); }
    private double graphValue(FramePoint point) {
        return switch (graphMetric) {
            case 0 -> point.frameMs() > 0 ? 1000 / point.frameMs() : 0;
            case 1 -> point.frameMs();
            case 2 -> point.measuredRenderMs();
            case 3 -> point.entityRenderMs();
            default -> point.blockEntityRenderMs();
        };
    }

    private void plotLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x0 += sx; }
            if (twice <= dx) { error += dx; y0 += sy; }
        }
    }

    private static boolean hovered(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    private static String tr(String key) { return component(key).getString(); }
    private static Component component(String key) { return Component.translatable("renderscope." + key); }
    private static String onOff(boolean value) { return tr(value ? "on" : "off"); }
    private static String decimal(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String integer(double value) { return String.format(Locale.ROOT, "%.0f", value); }
    private static String ms(double value) { return decimal(value) + " ms"; }
    private static String coordinate(double value) { return Math.abs(value - Math.rint(value)) < .01 ? integer(value) : String.format(Locale.ROOT, "%.1f", value); }
    private static String position(Position p) { return coordinate(p.x()) + " " + coordinate(p.y()) + " " + coordinate(p.z()); }
    private static String renderTypes(Statistics stats) { return stats.renderTypes().isEmpty() ? "—" : String.join(", ", stats.renderTypes()); }
    private static String compact(double value) {
        if (Math.abs(value) >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000);
        if (Math.abs(value) >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000);
        return value >= 100 || Math.abs(value - Math.rint(value)) < .01 ? integer(value) : String.format(Locale.ROOT, "%.1f", value);
    }
    private static String bytes(double value) {
        if (value >= 1024 * 1024) return decimal(value / (1024 * 1024)) + " MiB";
        if (value >= 1024) return decimal(value / 1024) + " KiB";
        return integer(value) + " B";
    }
    private static int next(int value, int... choices) {
        for (int i = 0; i < choices.length; i++) if (choices[i] == value) return choices[(i + 1) % choices.length];
        return choices[0];
    }
    private static double nextDouble(double value, double... choices) {
        for (int i = 0; i < choices.length; i++) if (Math.abs(choices[i] - value) < .000001) return choices[(i + 1) % choices.length];
        return choices[0];
    }
    private void setStatus(String value) { status = value == null ? "" : value; statusUntil = System.nanoTime() + 7_000_000_000L; }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
