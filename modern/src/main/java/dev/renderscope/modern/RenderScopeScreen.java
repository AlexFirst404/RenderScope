package dev.renderscope.modern;

import com.mojang.blaze3d.platform.InputConstants;
import dev.renderscope.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.*;
import java.util.function.Consumer;

/** Native 26.3 UI. Render extraction replaces the old GuiGraphics/render Screen contract. */
public final class RenderScopeScreen extends Screen {
    private enum Tab { OVERVIEW, MODS, OBJECTS, INSTANCES, TIMELINE, HEATMAP, CAPTURE, SETTINGS }
    private final Screen parent;
    private Tab tab = Tab.OVERVIEW;
    private ProfilerSnapshot snapshot = ModernHooks.PROFILER.snapshot();
    private DiagnosticReport diagnostic = DiagnosticEngine.analyze(snapshot);
    private Rows rows;
    private EditBox search;
    private String query = "", selectedMod = "";
    private AggregateMetric selectedObject;
    private InstanceKey selectedInstance;
    private FramePoint selectedFrame;
    private int top, bottom, sortColumn;
    private boolean descending = true;
    private String status = "";
    private int graphMetric;
    private Button captureButton, exportButton, findButton, instanceDetailsButton;
    private InstanceMetric displayedInstanceDetails;

    public RenderScopeScreen(Screen parent) { super(Component.literal("RenderScope")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override protected void init() {
        rows = null; search = null; findButton = null; exportButton = null; instanceDetailsButton = null; displayedInstanceDetails = null;
        int columns = Math.max(2, Math.min(8, (width - 20) / 70));
        int tabWidth = (width - 20) / columns;
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab choice = tabs[i];
            Button button = button(tabName(choice), 10 + i % columns * tabWidth, 39 + i / columns * 22,
                tabWidth - 3, ignored -> {
                    tab = choice; query = "";
                    if (choice == Tab.OBJECTS || choice == Tab.INSTANCES) { selectedMod = ""; selectedObject = null; selectedInstance = null; }
                    rebuildWidgets();
                });
            button.active = choice != tab;
        }
        top = 44 + ((tabs.length + columns - 1) / columns) * 22;
        bottom = height - 29;
        captureButton = button(captureLabel(), 10, height - 24, Math.min(130, (width-30)/3), ignored -> toggleCapture());
        button(tr("Settings", "Настройки"), Math.max(145, width/2 - 45), height - 24, 90,
            ignored -> { tab = Tab.SETTINGS; rebuildWidgets(); });
        button(tr("Done", "Готово"), width - 80, height - 24, 70, ignored -> onClose());
        if (tab == Tab.MODS || tab == Tab.OBJECTS || tab == Tab.INSTANCES) initTable();
        else if (tab == Tab.HEATMAP || tab == Tab.SETTINGS) {
            rows = addRenderableWidget(new Rows(width - 20, Math.max(28, bottom - top), top));
            rows.setX(10); refreshRows();
        } else if (tab == Tab.OVERVIEW) {
            findButton = button(tr("Show the instance", "Показать объект"), 12, bottom - 25, Math.min(170, width/2 - 18), ignored -> {
                diagnostic.worstInstance().ifPresent(metric -> { ModernOverlay.toggleHighlight(metric.context().key()); minecraft.setScreenAndShow(null); });
            });
            button(tr("Inspect measured mods", "Изучить моды"), width/2 + 4, bottom - 25, Math.min(170, width/2 - 18),
                ignored -> { tab = Tab.MODS; rebuildWidgets(); });
        } else if (tab == Tab.CAPTURE) initCapture();
        else if (tab == Tab.TIMELINE) {
            button(graphLabel(), 12, top, 175, ignored -> { graphMetric = (graphMetric + 1) % 5; rebuildWidgets(); });
            button(tr("Highlight selected spike", "Выделить объект spike"), 12, bottom - 23, Math.min(210, width - 24), ignored -> {
                if (selectedFrame != null && selectedFrame.worstInstance() != null) {
                    ModernOverlay.toggleHighlight(selectedFrame.worstInstance().context().key()); minecraft.setScreenAndShow(null);
                }
            });
        }
        updateButtons();
    }
    private void initTable() {
        int searchWidth = Math.max(70, width - 204);
        search = addRenderableWidget(new EditBox(font, 12, top, searchWidth, 20, Component.literal(tr("Search", "Поиск"))));
        search.setHint(Component.literal(tr("Search registry or mod", "Поиск объекта или мода")));
        search.setValue(query); search.setResponder(value -> { query = value; refreshRows(); });
        button(sortLabel(), width - 184, top, 122, ignored -> { sortColumn = (sortColumn+1) % 6; rebuildWidgets(); });
        button(descending ? "↓" : "↑", width - 58, top, 44, ignored -> { descending = !descending; refreshRows(); });
        int footer = tab == Tab.INSTANCES ? 48 : 0;
        rows = addRenderableWidget(new Rows(width - 20, Math.max(28, bottom - top - 25 - footer), top + 25)); rows.setX(10);
        if (tab == Tab.INSTANCES) {
            instanceDetailsButton = button(tr("Select an instance for renderer details", "Выберите экземпляр для сведений о рендере"),
                10, bottom - 47, width - 20, ignored -> selectedMetric().ifPresent(metric -> {
                    minecraft.keyboardHandler.setClipboard(metric.context().rendererClass());
                }));
            instanceDetailsButton.setTooltipDelay(java.time.Duration.ZERO);
            int w = Math.max(58, (width-32)/3);
            button(tr("Highlight", "Выделить"), 10, bottom - 23, w, ignored -> selectedMetric().ifPresent(metric -> {
                ModernOverlay.toggleHighlight(metric.context().key()); minecraft.setScreenAndShow(null);
            }));
            button(tr("Track", "Следить"), 14 + w, bottom - 23, w, ignored -> selectedMetric().ifPresent(metric -> ModernOverlay.toggleTrack(metric.context().key())));
            button(tr("Copy position", "Копировать XYZ"), 18 + w*2, bottom - 23, w, ignored -> selectedMetric().ifPresent(metric -> {
                Position p = metric.context().position(); minecraft.keyboardHandler.setClipboard(String.format(Locale.ROOT, "%.2f %.2f %.2f", p.x(), p.y(), p.z()));
                status = tr("Position copied", "Позиция скопирована");
            }));
        }
        refreshRows();
    }
    private void initCapture() {
        int[] durations = {5, 10, 30, 0};
        int w = Math.max(55, (width-32)/4);
        for (int i=0;i<durations.length;i++) {
            int duration = durations[i];
            Button durationButton = button(duration == 0 ? tr("Manual", "Вручную") : duration + " s", 12+i*(w+2), top+26, w, ignored -> {
                ModernHooks.SETTINGS.captureDurationSeconds = duration; ModernHooks.applySettings(); rebuildWidgets();
            });
            durationButton.active = duration != ModernHooks.SETTINGS.captureDurationSeconds;
        }
        exportButton = button(tr("Export JSON", "Экспорт JSON"), 12, bottom - 24, 160, ignored -> {
            CaptureReport report = ModernHooks.PROFILER.lastCapture();
            if (report == null) return;
            try { status = ModernReportExporter.write(report, ModernHooks.SETTINGS.includeWorldPositions).getFileName().toString(); }
            catch (java.io.IOException failure) { status = tr("Could not save report: ", "Не удалось сохранить отчёт: ") + failure.getClass().getSimpleName(); }
        });
    }
    @Override public void tick() {
        ProfilerSnapshot next = ModernHooks.PROFILER.snapshot();
        if (next != snapshot) { snapshot = next; diagnostic = DiagnosticEngine.analyze(next); refreshRows(); }
        updateButtons();
    }
    private void updateButtons() {
        if (captureButton != null) captureButton.setMessage(Component.literal(captureLabel()));
        if (exportButton != null) exportButton.active = ModernHooks.PROFILER.lastCapture() != null;
        if (findButton != null) {
            findButton.active = diagnostic.readiness() != DiagnosticReport.Readiness.DISABLED && diagnostic.worstInstance().isPresent();
            findButton.setMessage(Component.literal(diagnostic.ready() ? tr("Show the instance", "Показать объект")
                : tr("Show preliminary candidate", "Показать кандидата")));
        }
        if (instanceDetailsButton != null) {
            Optional<InstanceMetric> selected = selectedMetric();
            instanceDetailsButton.active = selected.isPresent();
            if (selected.isPresent() && selected.get() != displayedInstanceDetails) {
                var metric = selected.get();
                displayedInstanceDetails = metric;
                var context = metric.context();
                String owners = tr("Registry: ", "Объект: ") + ModCatalog.displayName(context.registryOwner())
                    + "  ·  " + tr("Renderer: ", "Рендер: ") + ModCatalog.displayName(context.rendererOwner());
                instanceDetailsButton.setMessage(Component.literal(font.plainSubstrByWidth(owners, width - 34)));
                StringBuilder details = new StringBuilder(context.registryId())
                    .append('\n').append(tr("Registry owner: ", "Владелец объекта: ")).append(ModCatalog.displayName(context.registryOwner()))
                    .append(" (").append(context.registryOwner()).append(")\n")
                    .append(tr("Renderer implementation: ", "Реализация рендера: ")).append(context.rendererClass())
                    .append('\n').append(tr("Renderer owner: ", "Владелец рендера: ")).append(ModCatalog.displayName(context.rendererOwner()))
                    .append(" (").append(context.rendererOwner()).append(")\nP95 ").append(ms(metric.stats().p95Ms()))
                    .append("  Peak ").append(ms(metric.stats().peakMs()));
                metric.stats().sources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    details.append('\n').append(entry.getKey() == MeasurementSource.RENDER_EXTRACTION ? tr("CPU extraction: ", "CPU подготовка: ")
                        : entry.getKey() == MeasurementSource.RENDER_SUBMISSION ? tr("CPU submission: ", "CPU отправка: ") : entry.getKey().name() + ": ")
                        .append(ms(entry.getValue().averageMs())).append(tr("/frame", "/кадр")));
                details.append('\n').append(tr("Click to copy the renderer class", "Нажмите, чтобы скопировать класс рендера"));
                instanceDetailsButton.setTooltip(Tooltip.create(Component.literal(details.toString())));
            } else if (selected.isEmpty()) {
                displayedInstanceDetails = null;
                instanceDetailsButton.setMessage(Component.literal(tr("Select an instance for renderer details", "Выберите экземпляр для сведений о рендере")));
                instanceDetailsButton.setTooltip(null);
            }
        }
    }
    private void toggleCapture() {
        if (snapshot.capture().active()) ModernHooks.PROFILER.stopCapture();
        else ModernHooks.PROFILER.startCapture(ModernHooks.SETTINGS.captureDurationSeconds);
        snapshot = ModernHooks.PROFILER.snapshot(); diagnostic = DiagnosticEngine.analyze(snapshot); updateButtons();
    }
    private void refreshRows() {
        if (rows == null) return;
        List<Row> result = new ArrayList<>();
        if (tab == Tab.HEATMAP || tab == Tab.SETTINGS) { settingsRows(result); rows.replaceEntries(result); return; }
        String lower = query.toLowerCase(Locale.ROOT);
        if (tab == Tab.INSTANCES) {
            snapshot.instances().stream().filter(metric -> selectedObject == null || selectedObject.id().equals(metric.context().registryId())
                    && selectedObject.kind() == metric.context().key().kind())
                .filter(metric -> selectedMod.isEmpty() || selectedMod.equals(metric.context().registryOwner()))
                .filter(metric -> matches(metric.context().registryId(), metric.context().registryOwner(), lower))
                .sorted(instanceComparator()).forEach(metric -> result.add(new Row(metric.context().registryId(),
                    formatStats(metric.stats()) + "  " + position(metric.context().position()), metric.stats(), ignored -> { selectedInstance = metric.context().key(); })));
        } else {
            List<AggregateMetric> metrics = tab == Tab.MODS ? snapshot.mods() : snapshot.objects();
            metrics.stream().filter(metric -> tab == Tab.MODS || selectedMod.isEmpty() || selectedMod.equals(metric.registryOwner()))
                .filter(metric -> matches(metric.id(), metric.registryOwner(), lower)).sorted(aggregateComparator())
                .forEach(metric -> result.add(new Row(tab == Tab.MODS ? ModCatalog.displayName(metric.id()) : metric.id(),
                    formatStats(metric.stats()) + (metric.kind() == null ? "" : "  " + metric.kind()), metric.stats(), ignored -> {
                        if (tab == Tab.MODS) { selectedMod = metric.id(); selectedObject = null; tab = Tab.OBJECTS; }
                        else { selectedObject = metric; tab = Tab.INSTANCES; }
                        query = ""; selectedInstance = null; rebuildWidgets();
                    })));
        }
        if (result.isEmpty()) result.add(new Row(tr("No measured objects here", "Здесь пока нет измеренных объектов"),
            tr("Look at a real entity or block entity; filters may hide results.", "Посмотрите на сущности/блок-сущности; проверьте фильтры."), null, ignored -> {}));
        rows.replaceEntries(result);
    }
    private void settingsRows(List<Row> result) {
        var s = ModernHooks.SETTINGS;
        if (tab == Tab.SETTINGS) {
            option(result, tr("Profiling", "Измерение"), on(s.profiling), () -> s.profiling = !s.profiling);
            option(result, tr("Thread CPU timer", "Таймер CPU потока"), on(s.cpuTiming), () -> s.cpuTiming = !s.cpuTiming);
            option(result, tr("Sample window", "Окно выборки"), s.sampleWindowSeconds + " s", () -> s.sampleWindowSeconds = next(s.sampleWindowSeconds, new int[]{1,3,5,10,30}));
            option(result, tr("Crosshair inspector", "Инспектор под прицелом"), on(s.inspect), () -> s.inspect = !s.inspect);
            option(result, tr("Include world positions in reports", "Координаты в отчётах"), on(s.includeWorldPositions), () -> s.includeWorldPositions = !s.includeWorldPositions);
            option(result, tr("Highlight timeout", "Время выделения"), s.highlightTimeoutSeconds + " s (0=manual)", () -> s.highlightTimeoutSeconds = next(s.highlightTimeoutSeconds, new int[]{5,15,30,60,0}));
            result.add(new Row(tr("Timing Only · extract + submit", "Только CPU · extract + submit"),
                tr("Geometry and GPU timings are unavailable in this render-state adapter.", "Геометрия и GPU-время недоступны в этом адаптере render state."), null, ignored -> {}));
        } else {
            option(result, tr("World heatmap", "Теплокарта мира"), on(s.heatmap), () -> s.heatmap = !s.heatmap);
            option(result, tr("Entities", "Сущности"), on(s.heatmapEntities), () -> s.heatmapEntities = !s.heatmapEntities);
            option(result, tr("Block entities", "Блок-сущности"), on(s.heatmapBlockEntities), () -> s.heatmapBlockEntities = !s.heatmapBlockEntities);
            option(result, tr("Maximum distance", "Максимальная дистанция"), s.maximumDistance + " blocks", () -> s.maximumDistance = next(s.maximumDistance, new int[]{16,32,64,96,128,256}));
            option(result, tr("Maximum objects", "Максимум объектов"), Integer.toString(s.maxHighlightedObjects), () -> s.maxHighlightedObjects = next(s.maxHighlightedObjects, new int[]{25,50,100,250,500}));
            option(result, tr("Minimum measured cost", "Минимальная стоимость"), ms(s.minimumCostMs), () -> s.minimumCostMs = s.minimumCostMs < 0.01 ? 0.01 : s.minimumCostMs < 0.05 ? 0.05 : s.minimumCostMs < 0.1 ? 0.1 : s.minimumCostMs < 0.5 ? 0.5 : 0);
            option(result, tr("Show labels", "Показывать подписи"), on(s.showLabels), () -> s.showLabels = !s.showLabels);
            option(result, tr("Through walls", "Через стены"), on(s.throughWalls), () -> s.throughWalls = !s.throughWalls);
        }
    }
    private void option(List<Row> rows, String name, String value, Runnable action) {
        rows.add(new Row(name + ": " + value, tr("Click to change", "Нажмите, чтобы изменить"), null,
            ignored -> { action.run(); ModernHooks.applySettings(); refreshRows(); }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        // Screen's final extraction wrapper has already extracted its background once.
        g.centeredText(font, "RenderScope", width/2, 9, 0xffffffff);
        String header = String.format(Locale.ROOT, "%s  ·  %.0f FPS  ·  %.2f ms/frame  ·  CPU extract + submit",
            snapshot.enabled() ? "LIVE" : "OFF", snapshot.performance().fps(), snapshot.performance().averageFrameMs());
        g.centeredText(font, font.plainSubstrByWidth(header, width-20), width/2, 24, 0xffa6d8e9);
        if (tab == Tab.OVERVIEW) overview(g);
        else if (tab == Tab.TIMELINE) timeline(g);
        else if (tab == Tab.CAPTURE) capture(g);
        if (!status.isEmpty()) g.text(font, font.plainSubstrByWidth(status, width-24), 12, height-39, 0xffffd783);
        super.extractRenderState(g, mouseX, mouseY, partial);
    }
    private void overview(GuiGraphicsExtractor g) {
        int y = top + 3;
        String headline = switch (diagnostic.readiness()) {
            case DISABLED -> tr("Profiling is disabled", "Измерение выключено");
            case NO_SAMPLES -> tr("Look at entities or block entities to collect data", "Посмотрите на сущности или блок-сущности");
            case COLLECTING -> tr("Early measurements", "Предварительные измерения");
            case READY -> tr("Most measured object CPU", "Больше всего измеренной CPU-работы");
        };
        Component headlineText = Component.literal(headline);
        g.textWithWordWrap(font, headlineText, 12, y, width-24, 0xff65d9ff);
        y += font.split(headlineText, width-24).size() * font.lineHeight + 4;
        if (diagnostic.topMod().isPresent()) {
            var topMod = diagnostic.topMod().get();
            g.text(font, ModCatalog.displayName(topMod.mod().id()) + "  " + ms(topMod.mod().stats().averageMs()) + "  "
                + String.format(Locale.ROOT, "%.1f%%", topMod.measuredCpuShare()*100) + tr(" of measured CPU", " измеренного CPU"), 12, y, 0xffffffff);
            y += 14;
        }
        String metrics = String.format(Locale.ROOT, "P50 %.2f  P95 %.2f  P99 %.2f ms  ·  1%% low %.0f FPS",
            snapshot.performance().p50FrameMs(), snapshot.performance().p95FrameMs(), snapshot.performance().p99FrameMs(), snapshot.performance().onePercentLowFps());
        if (y+18 < bottom-25) { g.text(font, font.plainSubstrByWidth(metrics, width-24), 12, y, 0xffc7d2d9); y+=15; }
        String caveat = tr("Measured CPU work, not the full rendering cost, GPU time or predicted FPS gain. Geometry/GPU unavailable here.",
            "Это измеренная CPU-работа, не весь рендер, GPU-время или обещание прироста FPS. Геометрия/GPU недоступны.");
        if (y < bottom-28) g.textWithWordWrap(font, Component.literal(caveat), 12, y, width-24, 0xffa6b7c4);
    }
    private void capture(GuiGraphicsExtractor g) {
        g.text(font, snapshot.capture().active() ? String.format(Locale.ROOT, "Recording %.1f s", snapshot.capture().elapsedSeconds())
            : tr("Choose duration, then Start Capture", "Выберите длительность и начните запись"), 12, top+5, 0xff65d9ff);
        CaptureReport capture = ModernHooks.PROFILER.lastCapture();
        if (capture == null) return;
        int y = top+58;
        g.text(font, String.format(Locale.ROOT, "%.2f s · %d frames · %.0f FPS · 1%% low %.0f", capture.durationSeconds(), capture.frames(),
            capture.performance().fps(), capture.performance().onePercentLowFps()), 12, y, 0xffffffff);
        if (!capture.mods().isEmpty()) g.text(font, tr("Top measured mod: ", "Лидер измерений: ") + ModCatalog.displayName(capture.mods().get(0).id()), 12, y+15, 0xffc7d2d9);
        g.text(font, tr("World positions in report: ", "Координаты в отчёте: ") + on(ModernHooks.SETTINGS.includeWorldPositions), 12, y+30, 0xffa6b7c4);
    }
    private void timeline(GuiGraphicsExtractor g) {
        int x0=12, x1=width-12, y0=top+26, y1=Math.max(y0+20,bottom-67);
        g.fill(x0,y0,x1,y1,0x99101620);
        List<FramePoint> points=snapshot.timeline();
        double max=points.stream().mapToDouble(this::graphValue).max().orElse(1); max=Math.max(1,max);
        for(int i=0;i<points.size();i++) {
            FramePoint point=points.get(i);
            int x=x0+(int)((long)i*(x1-x0)/Math.max(1,points.size()));
            int y=y1-(int)(graphValue(point)/max*(y1-y0-2));
            g.fill(x,y,Math.min(x1,x+Math.max(1,(x1-x0)/Math.max(1,points.size()))),y1,point.spike()?0xffff6876:0xff65d9ff);
        }
        if(selectedFrame!=null) {
            g.text(font, "Frame " + ms(selectedFrame.frameMs()) + " · CPU " + ms(selectedFrame.measuredRenderMs()), 12,y1+5,0xffffffff);
            String owners=selectedFrame.topModMs().entrySet().stream().map(e->ModCatalog.displayName(e.getKey())+" "+ms(e.getValue())).reduce((a,b)->a+"  "+b).orElse(tr("No attributed objects", "Нет измеренных объектов"));
            g.text(font,font.plainSubstrByWidth(owners,width-24),12,y1+18,0xffc7d2d9);
        }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT && tab==Tab.TIMELINE && event.x()>=12 && event.x()<width-12 && event.y()>=top+26 && event.y()<bottom-67 && !snapshot.timeline().isEmpty()) {
            int index=Math.min(snapshot.timeline().size()-1,(int)((event.x()-12)/(width-24)*snapshot.timeline().size()));
            selectedFrame=snapshot.timeline().get(Math.max(0,index)); return true;
        }
        return super.mouseClicked(event,doubleClick);
    }
    private Optional<InstanceMetric> selectedMetric() { return snapshot.instances().stream().filter(metric->metric.context().key().equals(selectedInstance)).findFirst(); }
    private Comparator<AggregateMetric> aggregateComparator() {
        Comparator<AggregateMetric> comparator=sortColumn==5?Comparator.comparing(AggregateMetric::id):Comparator.comparingDouble(metric->sortValue(metric.stats()));
        return descending?comparator.reversed():comparator;
    }
    private Comparator<InstanceMetric> instanceComparator() {
        Comparator<InstanceMetric> comparator=sortColumn==5?Comparator.comparing(metric->metric.context().registryId()):Comparator.comparingDouble(metric->sortValue(metric.stats()));
        return descending?comparator.reversed():comparator;
    }
    private double sortValue(Statistics s) { return switch(sortColumn) { case 1->s.p95Ms();case 2->s.peakMs();case 3->s.calls();case 4->s.instances();default->s.averageMs(); }; }
    private double graphValue(FramePoint p) { return switch(graphMetric) { case 1->p.frameMs()>0?1000/p.frameMs():0;case 2->p.measuredRenderMs();case 3->p.entityRenderMs();case 4->p.blockEntityRenderMs();default->p.frameMs(); }; }
    private String graphLabel() { return new String[]{"Frame ms","FPS","Measured CPU ms","Entity CPU ms","Block entity CPU ms"}[graphMetric]; }
    private String sortLabel() { return tr("Sort: ","Порядок: ")+new String[]{"Avg","P95","Peak","Calls","Instances","Name"}[sortColumn]; }
    private String captureLabel() { return snapshot.capture().active()?tr("Stop Capture","Остановить"):tr("Start Capture","Начать запись"); }
    private boolean matches(String id,String owner,String query) { return query.isEmpty()||id.toLowerCase(Locale.ROOT).contains(query)||ModCatalog.displayName(owner).toLowerCase(Locale.ROOT).contains(query); }
    private static String formatStats(Statistics s) { return String.format(Locale.ROOT,"%.3f ms  P95 %.3f  peak %.3f  · %.1f calls/f  · %d instances",s.averageMs(),s.p95Ms(),s.peakMs(),s.callsPerFrame(),s.instances()); }
    private static String position(Position p) { return String.format(Locale.ROOT,"X %.1f Y %.1f Z %.1f",p.x(),p.y(),p.z()); }
    private static String ms(double value) { return String.format(Locale.ROOT,"%.3f ms",value); }
    private static int next(int value,int[] choices) { for(int i=0;i<choices.length;i++)if(value==choices[i])return choices[(i+1)%choices.length];return choices[0]; }
    private static String on(boolean value) { return value?"ON":"OFF"; }
    private static String tr(String en,String ru) { return Minecraft.getInstance().options.languageCode.startsWith("ru")?ru:en; }
    private String tabName(Tab tab) { return switch(tab) { case OVERVIEW->tr("Overview","Обзор");case MODS->tr("Mods","Моды");case OBJECTS->tr("Objects","Объекты");case INSTANCES->tr("Instances","Экземпляры");case TIMELINE->tr("Timeline","График");case HEATMAP->tr("Heatmap","Теплокарта");case CAPTURE->tr("Capture","Запись");case SETTINGS->tr("Settings","Настройки"); }; }
    private Button button(String label,int x,int y,int width,Button.OnPress action) { return addRenderableWidget(Button.builder(Component.literal(label),action).bounds(x,y,Math.max(30,width),20).build()); }
    private final class Rows extends ObjectSelectionList<Row> {
        Rows(int width,int height,int y) { super(RenderScopeScreen.this.minecraft,width,height,y,30); }
        @Override public int getRowWidth() { return getWidth()-14; }
    }
    private final class Row extends ObjectSelectionList.Entry<Row> {
        private final String title,detail; private final Statistics stats; private final Consumer<Row> action;
        Row(String title,String detail,Statistics stats,Consumer<Row> action) { this.title=title;this.detail=detail;this.stats=stats;this.action=action; }
        @Override public Component getNarration() { return Component.literal(title+" "+detail); }
        @Override public void extractContent(GuiGraphicsExtractor g,int mouseX,int mouseY,boolean hovered,float partial) {
            g.text(font,font.plainSubstrByWidth(title,getContentWidth()),getContentX(),getContentY()+1,0xffffffff);
            g.text(font,font.plainSubstrByWidth(detail,getContentWidth()),getContentX(),getContentY()+13,0xffa6b7c4);
        }
        @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
            if(event.button()!=InputConstants.MOUSE_BUTTON_LEFT) return false;
            rows.setSelected(this);action.accept(this);return true;
        }
    }
}
