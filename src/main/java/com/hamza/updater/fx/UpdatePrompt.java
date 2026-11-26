package com.hamza.updater.fx;

import com.hamza.updater.AppUpdater;
import com.hamza.updater.CheckResult;
import com.hamza.updater.Download;
import com.hamza.updater.Os;
import com.hamza.updater.Release;
import com.hamza.updater.Version;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The window over {@link AppUpdater}: it looks for a release in the background, tells the person when there
 * is one, downloads it with a progress bar, and closes the program for the installer.
 *
 * <pre>{@code
 * UpdatePrompt updates = UpdatePrompt.create(updater, primaryStage)
 *         .language("ar")
 *         .start();                 // a look shortly after the start, then every six hours
 * checkItem.setOnAction(e -> updates.checkNow());   // "Check for updates" in a menu
 * }</pre>
 *
 * <p>A look the program made on its own says nothing unless there is a release, never shows one the person
 * skipped, and asks about a release once a run. A look the person asked for ({@link #checkNow}) always
 * answers. While the program's window is hidden - in the tray - the announcement waits for it to be shown.
 * A program that announces releases its own way - a card in a side bar - says so with {@link #onAvailable}.
 *
 * <p>Every method is called on the JavaFX thread; the network is never touched on it.
 */
public final class UpdatePrompt {

    private static final Duration FIRST_LOOK = Duration.ofSeconds(8);

    private final AppUpdater updater;
    private final Window owner;
    private Texts texts = Texts.of(Locale.getDefault().getLanguage());
    private Duration interval = Duration.ofHours(6);
    private Runnable exit = UpdatePrompt::closeProgram;
    private boolean enforce;
    private boolean ahead;
    private Consumer<Announcement> announcer;

    private ScheduledExecutorService background;
    private boolean showing;
    private boolean checking;
    private Version declined;
    private ChangeListener<Boolean> whenShown;

    private UpdatePrompt(AppUpdater updater, Window owner) {
        this.updater = Objects.requireNonNull(updater);
        this.owner = owner;
    }

    /** @param owner the program's main window; null for a program without one */
    public static UpdatePrompt create(AppUpdater updater, Window owner) {
        return new UpdatePrompt(updater, owner);
    }

    /**
     * The window's language: {@code "ar"} for Arabic, anything else for English. The computer's language
     * unless said; may be called again when the program's language changes.
     */
    public UpdatePrompt language(String code) {
        this.texts = Texts.of(code);
        return this;
    }

    /** How often the program looks while it runs. Six hours unless said; call before {@link #start}. */
    public UpdatePrompt checkEvery(Duration every) {
        if (every.compareTo(Duration.ofMinutes(5)) < 0) {
            throw new IllegalArgumentException("Looking more often than every five minutes helps nobody");
        }
        this.interval = every;
        return this;
    }

    /**
     * How the program closes once the installer is waiting for it - a program with a tray icon or work to
     * save closes its own way. {@link Platform#exit()} unless said, with the process ended a few seconds
     * later whatever still runs.
     */
    public UpdatePrompt onExit(Runnable exit) {
        this.exit = Objects.requireNonNull(exit);
        return this;
    }

    /**
     * Makes a required update the only way on. A release the publisher marked required is never skippable;
     * with this, "Later" goes too: the choice is to update or to close the program. For a program an old
     * copy of which must not keep working - only the program knows whether that is so, which is why it is
     * not the default. A look that gets no answer (no connection) still lets the program run.
     */
    public UpdatePrompt enforceRequired(boolean enforce) {
        this.enforce = enforce;
        return this;
    }

    /**
     * Downloads a release the program found on its own before asking, so that the person is told "version
     * 2.5 is ready to install" and pressing the button installs at once, with no wait for a download. A
     * release the person skipped is not downloaded, and one that could not be fetched is offered the
     * ordinary way. Off unless said: it spends the person's connection - tens of megabytes - unasked.
     */
    public UpdatePrompt downloadInBackground(boolean ahead) {
        this.ahead = ahead;
        return this;
    }

    /**
     * Hands a release the program found on its own to the program instead of opening the window, so that
     * it can announce it its own way - a card in a side bar, a badge - and open the window when the person
     * asks ({@link Announcement#update}). Called on the JavaFX thread, at every look that finds a release
     * the person did not skip or put off this run. A required release opens the window as well. A look the
     * person asked for ({@link #checkNow}) still answers with the window. Call before {@link #start}.
     */
    public UpdatePrompt onAvailable(Consumer<Announcement> announcer) {
        this.announcer = Objects.requireNonNull(announcer);
        return this;
    }

    /** Starts looking: once shortly after now, then at every interval, until {@link #stop}. */
    public UpdatePrompt start() {
        background().scheduleWithFixedDelay(this::lookQuietly, FIRST_LOOK.toMillis(), interval.toMillis(),
                TimeUnit.MILLISECONDS);
        return this;
    }

    /** Looks now and answers whatever it finds - for a "Check for updates" button. */
    public void checkNow() {
        if (showing || checking) {
            return;
        }
        checking = true;
        // A thread of its own: the background one may be busy downloading a release ahead of the question.
        Thread look = new Thread(() -> {
            CheckResult result = updater.check();
            Platform.runLater(() -> {
                checking = false;
                answer(result);
            });
        }, "update-check-now");
        look.setDaemon(true);
        look.start();
    }

    /**
     * Looks now, the way the program does on its own: it says nothing unless there is a release to offer.
     * For the moment a program learns it may be out of date - a server that refused its version, say.
     */
    public void checkQuietly() {
        background().execute(this::lookQuietly);
    }

    public void stop() {
        if (background != null) {
            background.shutdownNow();
            background = null;
        }
    }

    private ScheduledExecutorService background() {
        if (background == null) {
            background = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "update-check");
                thread.setDaemon(true);
                return thread;
            });
        }
        return background;
    }

    private void lookQuietly() {
        try {
            if (updater.check() instanceof CheckResult.Available available
                    && (available.required() || !updater.isSkipped(available.release().version()))) {
                boolean ready = ahead && updater.canInstall() && fetchedAhead(available.release());
                if (!Thread.currentThread().isInterrupted()) {
                    Platform.runLater(() -> announce(available, ready));
                }
            }
        } catch (RuntimeException unexpected) {
            // One look that went wrong must not end the looks after it.
        }
    }

    /**
     * Downloads the installer before anybody is asked, so that the question is "install it?" and not
     * "wait for it?". A download that does not finish is no failure: the offer then fetches what is
     * missing with the person watching.
     */
    private boolean fetchedAhead(Release release) {
        try {
            updater.download(release, Download.Progress.NONE);
            return true;
        } catch (IOException | RuntimeException notNow) {
            return false;
        }
    }

    /**
     * A release the program found on its own, on a window that is shown: once a run when it may be put
     * off, at every look when it is required.
     */
    private void announce(CheckResult.Available available, boolean ready) {
        if (showing || (!available.required() && available.release().version().equals(declined))) {
            return;
        }
        if (owner != null && !owner.isShowing()) {
            if (whenShown == null) {
                whenShown = (property, was, shown) -> {
                    if (shown) {
                        owner.showingProperty().removeListener(whenShown);
                        whenShown = null;
                        Platform.runLater(() -> announce(available, ready));
                    }
                };
                owner.showingProperty().addListener(whenShown);
            }
            return;
        }
        if (announcer != null) {
            announcer.accept(new Announcement(this, available, ready));
            if (!available.required()) {
                return;
            }
        }
        offer(available, ready);
    }

    /** The window for a release the program announced, when the person asks for it. */
    void offerFound(CheckResult.Available available, boolean ready) {
        if (!showing && !checking) {
            offer(available, ready);
        }
    }

    private void answer(CheckResult result) {
        switch (result) {
            case CheckResult.Available available -> offer(available, false);
            case CheckResult.UpToDate upToDate ->
                    message(Alert.AlertType.INFORMATION, texts.upToDate().formatted(upToDate.current()), "");
            case CheckResult.NotInstalled notInstalled ->
                    message(Alert.AlertType.INFORMATION, texts.notInstalled(), "");
            case CheckResult.Failed failed -> message(Alert.AlertType.WARNING, texts.checkFailed(), failed.reason());
        }
    }

    /** @param ready the installer is already downloaded: the question is whether to install it */
    private void offer(CheckResult.Available available, boolean ready) {
        Release release = available.release();
        boolean installs = updater.canInstall();
        // Linux and macOS: the installer is downloaded, checked and opened; the person finishes it.
        boolean opens = updater.canOpen();
        Optional<URI> page = release.page();
        boolean required = available.required();
        boolean onlyWayOn = required && enforce;
        ButtonType update = new ButtonType(
                opens ? texts.downloadInstaller() : !installs ? texts.openPage() : ready ? texts.installNow() : texts.updateNow(),
                ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(onlyWayOn ? texts.closeProgram() : texts.later(),
                ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skip = new ButtonType(texts.skip(), ButtonBar.ButtonData.LEFT);

        Alert alert = new Alert(required ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
        alert.setTitle(texts.title());
        alert.setHeaderText((required ? texts.requiredHeader() : ready ? texts.readyHeader() : texts.available())
                .formatted(release.version(), updater.appName()));
        VBox content = new VBox(8);
        if (required) {
            content.getChildren().add(wrapped(texts.requiredText()));
        }
        content.getChildren().add(wrapped(texts.current().formatted(available.current())));
        if (!release.notes().isBlank()) {
            content.getChildren().addAll(new Label(texts.whatsNew()), NotesView.of(release.notes(), UpdatePrompt::open));
        }
        if (installs) {
            content.getChildren().add(wrapped(texts.willRestart()));
        }
        alert.getDialogPane().setContent(content);
        // A required release cannot be skipped: the publisher said older copies must not stay.
        List<ButtonType> buttons = new ArrayList<>();
        if (installs || opens || page.isPresent()) {
            buttons.add(update);
        }
        buttons.add(later);
        if (!required) {
            buttons.add(skip);
        }
        alert.getButtonTypes().setAll(buttons);
        dress(alert);

        showing = true;
        Optional<ButtonType> chosen = alert.showAndWait();
        showing = false;
        if (chosen.isPresent() && chosen.get() == update) {
            List<String> others = installs ? updater.runningAlongside() : List.of();
            if (!others.isEmpty()) {
                // The installer could not replace files these still hold; the offer comes back once told.
                message(Alert.AlertType.WARNING, texts.closeOthers(), String.join("\n", others));
                offer(available, ready);
            } else if (installs || opens) {
                // Downloaded ahead or not, the same step: what is already there is checked and kept.
                download(available);
            } else {
                page.ifPresent(UpdatePrompt::open);
                if (onlyWayOn) {
                    close();
                }
            }
        } else if (onlyWayOn) {
            close();
        } else if (chosen.isPresent() && chosen.get() == skip) {
            updater.skip(release.version());
        } else {
            declined = release.version();
        }
    }

    /** Whether nothing but the update lets the program go on: the release is required and the program insists. */
    private boolean onlyWayOn(CheckResult.Available available) {
        return available.required() && enforce;
    }

    private void close() {
        stop();
        exit.run();
    }

    private void download(CheckResult.Available available) {
        Release release = available.release();
        ButtonType cancel = new ButtonType(texts.cancel(), ButtonBar.ButtonData.CANCEL_CLOSE);
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setPrefWidth(380);
        Label status = new Label(" ");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(texts.title());
        dialog.setHeaderText(texts.downloading());
        dialog.getDialogPane().setContent(new VBox(10, bar, status));
        dialog.getDialogPane().getButtonTypes().setAll(cancel);
        dress(dialog);

        Thread worker = new Thread(() -> {
            Path installer;
            try {
                installer = updater.download(release, (done, total) -> Platform.runLater(() -> {
                    bar.setProgress(total > 0 ? (double) done / total : ProgressBar.INDETERMINATE_PROGRESS);
                    status.setText(total > 0 ? texts.megabytes().formatted(megabytes(done), megabytes(total)) : " ");
                }));
            } catch (InterruptedIOException cancelled) {
                // Cancelling does not get a person past an update that is the only way on.
                if (onlyWayOn(available)) {
                    Platform.runLater(() -> offer(available, false));
                }
                return;
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> failed(dialog, available, texts.downloadFailed(), failure));
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                // Cancelled in the moment the download ended: nothing is installed behind a closed window.
                return;
            }
            if (!updater.canInstall()) {
                boolean opened;
                try {
                    updater.open(installer);
                    opened = true;
                } catch (IOException | RuntimeException noHandler) {
                    opened = false;
                }
                boolean worked = opened;
                Platform.runLater(() -> handedOver(dialog, available, installer, worked));
                return;
            }
            Platform.runLater(() -> {
                dialog.setHeaderText(texts.starting());
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                dialog.getDialogPane().lookupButton(cancel).setDisable(true);
            });
            try {
                updater.install(installer);
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> failed(dialog, available, texts.installFailed(), failure));
                return;
            }
            Platform.runLater(this::close);
        }, "update-download");
        worker.setDaemon(true);

        dialog.getDialogPane().lookupButton(cancel).addEventFilter(ActionEvent.ACTION, pressed -> worker.interrupt());
        dialog.setOnCloseRequest(closing -> worker.interrupt());
        dialog.setOnHidden(hidden -> showing = false);
        showing = true;
        dialog.show();
        worker.start();
    }

    /**
     * Linux and macOS: the installer is downloaded and the system was asked to open it. The person is told
     * where it is either way - a system with nothing to open it with still has the file.
     */
    private void handedOver(Dialog<?> progress, CheckResult.Available available, Path installer, boolean opened) {
        progress.setOnCloseRequest(null);
        progress.close();
        // A path reads left to right, in an Arabic window too.
        message(Alert.AlertType.INFORMATION, opened ? texts.openedInstaller() : texts.openFailed(),
                "‪" + installer + "‬");
        // Asked and answered: the same release is not offered again while this copy keeps running.
        declined = available.release().version();
        if (onlyWayOn(available)) {
            close();
        }
    }

    private void failed(Dialog<?> progress, CheckResult.Available available, String what, Exception failure) {
        Release release = available.release();
        progress.setOnCloseRequest(null);
        progress.close();
        ButtonType open = new ButtonType(texts.openPage(), ButtonBar.ButtonData.OK_DONE);
        ButtonType close = new ButtonType(texts.cancel(), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(texts.title());
        alert.setHeaderText(what);
        alert.getDialogPane().setContent(wrapped(String.valueOf(failure.getMessage())));
        alert.getButtonTypes().setAll(release.page().isPresent() ? List.of(open, close) : List.of(close));
        dress(alert);
        showing = true;
        Optional<ButtonType> chosen = alert.showAndWait();
        showing = false;
        if (chosen.isPresent() && chosen.get() == open) {
            release.page().ifPresent(UpdatePrompt::open);
        }
        if (onlyWayOn(available)) {
            offer(available, false);
        }
    }

    private void message(Alert.AlertType type, String text, String detail) {
        Alert alert = new Alert(type);
        alert.setTitle(texts.title());
        alert.setHeaderText(text);
        alert.getDialogPane().setContent(detail.isBlank() ? null : wrapped(detail));
        // In the window's language, not the computer's.
        alert.getButtonTypes().setAll(new ButtonType(texts.ok(), ButtonBar.ButtonData.OK_DONE));
        dress(alert);
        showing = true;
        alert.showAndWait();
        showing = false;
    }

    /** The owner's window, style sheets, icons and reading direction on a dialog of ours. */
    private void dress(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        pane.setNodeOrientation(texts.rightToLeft() ? NodeOrientation.RIGHT_TO_LEFT : NodeOrientation.LEFT_TO_RIGHT);
        if (owner == null) {
            return;
        }
        if (owner.isShowing()) {
            dialog.initOwner(owner);
        }
        if (owner.getScene() != null) {
            pane.getStylesheets().addAll(owner.getScene().getStylesheets());
        }
        if (owner instanceof Stage stage && pane.getScene() != null && pane.getScene().getWindow() instanceof Stage own) {
            own.getIcons().addAll(stage.getIcons());
        }
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(440);
        return label;
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /** A link in the release's notes. */
    private static void open(String address) {
        try {
            open(URI.create(address));
        } catch (IllegalArgumentException notAnAddress) {
            // A link that leads nowhere is left alone.
        }
    }

    /** Opens a web page in the person's browser. HTTPS only: the address came from the release. */
    static void open(URI page) {
        if (!"https".equalsIgnoreCase(page.getScheme())) {
            return;
        }
        Os os = Os.current();
        List<String> command = os == Os.WINDOWS
                ? List.of("rundll32", "url.dll,FileProtocolHandler", page.toString())
                : List.of(os == Os.MAC ? "open" : "xdg-open", page.toString());
        try {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException noBrowser) {
            // Nothing to open it with; the person still has the version number.
        }
    }

    private static void closeProgram() {
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException stopped) {
                return;
            }
            System.exit(0);
        }, "update-exit");
        guard.setDaemon(true);
        guard.start();
        Platform.exit();
    }
}
