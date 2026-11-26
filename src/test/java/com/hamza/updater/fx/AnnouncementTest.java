package com.hamza.updater.fx;

import com.hamza.updater.AppUpdater;
import com.hamza.updater.Release;
import com.hamza.updater.Version;
import javafx.application.Platform;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A release found on its own goes to the program's {@code onAvailable}, and the window waits to be asked for. */
class AnnouncementTest {

    private static boolean toolkit;

    @TempDir
    Path folder;

    private UpdatePrompt prompt;

    @BeforeAll
    static void startJavaFx() {
        try {
            Platform.startup(() -> { });
            toolkit = true;
        } catch (IllegalStateException alreadyStarted) {
            toolkit = true;
        } catch (UnsupportedOperationException | Error noDisplay) {
            toolkit = false;
        }
        if (toolkit) {
            Platform.setImplicitExit(false);
        }
    }

    @AfterEach
    void stopLooking() throws Exception {
        if (prompt != null) {
            prompt.stop();
        }
        if (toolkit) {
            onFx(() -> {
                for (Window window : List.copyOf(Window.getWindows())) {
                    window.hide();
                }
                return null;
            });
        }
    }

    private static Release release(String version, Optional<Version> minimum) {
        return new Release(Version.of(version), "App " + version, "## New\n- Faster", Optional.of(
                URI.create("https://github.com/o/a/releases/tag/v" + version)),
                new Release.Installer("App-setup.exe", URI.create("https://github.com/o/a/releases/download/x/App-setup.exe"),
                        10, Optional.empty()), false, minimum);
    }

    private AppUpdater updater(Release release) {
        return AppUpdater.builder("App").currentVersion("2.4.0").source(() -> Optional.of(release)).folder(folder).build();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.call());
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    private static boolean windowShown() throws Exception {
        return onFx(() -> Window.getWindows().stream().anyMatch(Window::isShowing));
    }

    @Test
    void aReleaseFoundOnItsOwnIsHandedToTheProgramWithoutAWindow() throws Exception {
        assumeTrue(toolkit, "needs a display for JavaFX");
        BlockingQueue<Announcement> found = new LinkedBlockingQueue<>();
        prompt = UpdatePrompt.create(updater(release("2.5.0", Optional.empty())), null).onAvailable(found::add);

        prompt.checkQuietly();

        Announcement announcement = found.poll(10, TimeUnit.SECONDS);
        assertNotNull(announcement);
        assertEquals(Version.of("2.5.0"), announcement.version());
        assertEquals(Version.of("2.4.0"), announcement.current());
        assertFalse(announcement.required());
        assertFalse(announcement.ready());
        assertTrue(announcement.notes().contains("Faster"));
        assertEquals(Optional.of(URI.create("https://github.com/o/a/releases/tag/v2.5.0")), announcement.page());
        assertFalse(windowShown(), "the program announces it; no window over the work");
    }

    @Test
    void updateOpensTheWindowForTheAnnouncedRelease() throws Exception {
        assumeTrue(toolkit, "needs a display for JavaFX");
        BlockingQueue<Announcement> found = new LinkedBlockingQueue<>();
        prompt = UpdatePrompt.create(updater(release("2.5.0", Optional.empty())), null).onAvailable(found::add);
        prompt.checkQuietly();
        Announcement announcement = found.poll(10, TimeUnit.SECONDS);
        assertNotNull(announcement);

        // The window waits for an answer, so it is opened without waiting for it here.
        Platform.runLater(announcement::update);

        assertTrue(waitFor(AnnouncementTest::windowShown), "the window opens when asked for");
    }

    @Test
    void aSkippedReleaseIsNotAnnounced() throws Exception {
        assumeTrue(toolkit, "needs a display for JavaFX");
        AppUpdater updater = updater(release("2.5.0", Optional.empty()));
        updater.skip(Version.of("2.5.0"));
        BlockingQueue<Announcement> found = new LinkedBlockingQueue<>();
        prompt = UpdatePrompt.create(updater, null).onAvailable(found::add);

        prompt.checkQuietly();

        assertNull(found.poll(1, TimeUnit.SECONDS));
        assertFalse(windowShown());
    }

    @Test
    void aRequiredReleaseIsAnnouncedAndStillOpensTheWindow() throws Exception {
        assumeTrue(toolkit, "needs a display for JavaFX");
        BlockingQueue<Announcement> found = new LinkedBlockingQueue<>();
        prompt = UpdatePrompt.create(updater(release("2.8.0", Optional.of(Version.of("2.5.0")))), null)
                .onAvailable(found::add);

        prompt.checkQuietly();

        Announcement announcement = found.poll(10, TimeUnit.SECONDS);
        assertNotNull(announcement);
        assertTrue(announcement.required());
        assertTrue(waitFor(AnnouncementTest::windowShown), "nobody misses a required release");
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private static boolean waitFor(Check check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (check.holds()) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
