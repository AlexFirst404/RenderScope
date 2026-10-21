package dev.renderscope.modern.smoke;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** Separate QA mod: actual main/server threads, native inputs and framebuffer readback. */
public final class NativeSmokeContext {
    private static final AtomicLong TICKS = new AtomicLong();
    private static boolean started;
    private static final Minecraft MC = Minecraft.getInstance();

    public static void tick() {
        TICKS.incrementAndGet();
        if (started || NativeSmokePlatform.currentScreen(MC) == null) return;
        started = true;
        Thread.ofPlatform().name("RenderScope native gameplay QA").start(() -> {
            try { new NativeModernSmoke().runTest(new NativeSmokeContext()); }
            catch (Throwable failure) { failure.printStackTrace(); }
            finally { MC.execute(MC::stop); }
        });
    }

    @FunctionalInterface public interface ClientFunction<T> { T apply(Minecraft client) throws Exception; }
    @FunctionalInterface public interface ClientAction { void accept(Minecraft client) throws Exception; }
    public <T> T computeOnClient(ClientFunction<T> action) {
        return MC.submit(() -> {
            try { return action.apply(MC); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        }).join();
    }
    public void runOnClient(ClientAction action) { computeOnClient(client -> { action.accept(client); return null; }); }
    public void restoreDefaultGameOptions() {
        runOnClient(client -> { client.options.guiScale().set(2); client.options.renderDistance().set(4); });
    }
    public void waitFor(Predicate<Minecraft> predicate, int maxTicks) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(15_000L, maxTicks * 50L));
        while (!computeOnClient(predicate::test)) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Native gameplay condition timed out after " + maxTicks + " ticks");
            pause();
        }
    }
    private static void pause() {
        try { Thread.sleep(25); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
    }
    public void waitTicks(int ticks) {
        long target = TICKS.get() + ticks;
        waitFor(client -> TICKS.get() >= target, Math.max(100, ticks * 4));
    }
    public void waitForScreen(Class<? extends Screen> type) { waitFor(client -> type.isInstance(NativeSmokePlatform.currentScreen(client)), 400); }
    public NativeSmokeContext getInput() { return this; }
    public void resizeWindow(int width, int height) { runOnClient(client -> client.getWindow().setWindowed(width, height)); }
    public void setCursorPos(double x, double y) {
        runOnClient(client -> NativeSmokePlatform.moveCursor(client, x, y));
        waitTicks(2);
    }
    public void pressKey(int key) {
        runOnClient(client -> {
            if (key == InputConstants.KEY_ESCAPE && NativeSmokePlatform.currentScreen(client) == null) client.pauseGame(false);
            else if (NativeSmokePlatform.currentScreen(client) != null) NativeSmokePlatform.currentScreen(client).keyPressed(new KeyEvent(key, 0, 0));
            else KeyMapping.click(InputConstants.getKey("key.keyboard.f8"));
        });
        waitTicks(2);
    }
    public void clickScreenButton(String text) {
        runOnClient(client -> {
            Screen screen = NativeSmokePlatform.currentScreen(client);
            if (screen == null) throw new IllegalStateException("No native screen for button " + text);
            AbstractButton button = screen.children().stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).filter(value -> value.getMessage().getString().equals(text)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing native button " + text));
            if (!button.active || !button.visible) throw new IllegalStateException("Native button unavailable " + text);
            button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        });
        waitTicks(2);
    }
    public NativeSmokeContext worldBuilder() { return this; }
    public TestWorld create() {
        waitFor(client -> NativeSmokePlatform.currentScreen(client) instanceof net.minecraft.client.gui.screens.TitleScreen, 1200);
        String name = "RenderScope-native-QA-" + System.currentTimeMillis();
        runOnClient(client -> client.createWorldOpenFlows().createFreshLevel(name,
                new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                        true, WorldDataConfiguration.DEFAULT), new WorldOptions(20261020L, false, false),
                provider -> provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                NativeSmokePlatform.currentScreen(client)));
        waitFor(client -> client.level != null && client.player != null && client.getSingleplayerServer() != null, 2400);
        runOnClient(client -> client.setScreenAndShow(null));
        return new TestWorld(this);
    }
    public static final class TestWorld implements AutoCloseable {
        private final NativeSmokeContext context;
        TestWorld(NativeSmokeContext context) { this.context = context; }
        public TestWorld getServer() { return this; }
        public void runCommand(String command) {
            var server = context.computeOnClient(Minecraft::getSingleplayerServer);
            server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)).join();
        }
        @Override public void close() { context.runOnClient(Minecraft::disconnectWithSavingScreen); }
    }
    public Path takeScreenshot(String name) throws java.io.IOException {
        Path path = MC.gameDirectory.toPath().resolve("screenshots").resolve(name + ".png");
        Files.createDirectories(path.getParent());
        CompletableFuture<Path> image = new CompletableFuture<>();
        runOnClient(client -> Screenshot.takeScreenshot(NativeSmokePlatform.mainTarget(client), pixels -> {
            try (pixels) { pixels.writeToFile(path); image.complete(path); }
            catch (Throwable failure) { image.completeExceptionally(failure); }
        }));
        try { return image.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new java.io.IOException("Native framebuffer screenshot failed", failure); }
    }
}
