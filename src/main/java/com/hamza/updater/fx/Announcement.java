package com.hamza.updater.fx;

import com.hamza.updater.CheckResult;
import com.hamza.updater.Release;
import com.hamza.updater.Version;
import javafx.application.Platform;

import java.net.URI;
import java.util.Optional;

/**
 * A release the program found on its own, handed to {@link UpdatePrompt#onAvailable} so that the program
 * announces it its own way - a card in a side bar, a badge on a menu - instead of a window over the work.
 *
 * <pre>{@code
 * updates.onAvailable(found -> {
 *     card.setText("Version " + found.version() + " is available");
 *     updateButton.setOnAction(e -> found.update());
 *     whatsNew.setOnAction(e -> found.openPage());
 *     card.setVisible(true);
 * });
 * }</pre>
 *
 * <p>Every method is called on the JavaFX thread.
 */
public final class Announcement {

    private final UpdatePrompt prompt;
    private final CheckResult.Available available;
    private final boolean ready;

    Announcement(UpdatePrompt prompt, CheckResult.Available available, boolean ready) {
        this.prompt = prompt;
        this.available = available;
        this.ready = ready;
    }

    /** The release's version: what the person is offered. */
    public Version version() {
        return release().version();
    }

    /** The running program's version. */
    public Version current() {
        return available.current();
    }

    /**
     * The publisher marked the release required for this copy. The window is shown as well, as it is
     * without an {@code onAvailable}, so that nobody misses it.
     */
    public boolean required() {
        return available.required();
    }

    /** The installer was downloaded ahead ({@link UpdatePrompt#downloadInBackground}): it installs at once. */
    public boolean ready() {
        return ready;
    }

    /** The release's description as written, in Markdown; empty when it has none. */
    public String notes() {
        return release().notes();
    }

    /** The release's web page, when the source has one. */
    public Optional<URI> page() {
        return release().page();
    }

    /**
     * Opens the update window for this release, with what is new, "Update now", "Later" and "Skip this
     * version": what the window would have shown on its own. Nothing happens while another window of the
     * prompt is open.
     *
     * <p>Returns at once: the window opens once the event that asked for it - a button's action - is over.
     * A window that waits for an answer inside that event is closed at once when the button was pressed
     * through UI Automation (a screen reader, a test tool), and the download starts unasked.
     */
    public void update() {
        Platform.runLater(() -> prompt.offerFound(available, ready));
    }

    /** Opens the release's page in the person's browser. Nothing happens when there is none. */
    public void openPage() {
        page().ifPresent(UpdatePrompt::open);
    }

    private Release release() {
        return available.release();
    }

    @Override
    public String toString() {
        return "Announcement[" + version() + (required() ? ", required" : "") + (ready ? ", ready" : "") + "]";
    }
}
