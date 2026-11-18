package com.hamza.updater.fx;

import com.hamza.updater.AppUpdater;
import com.hamza.updater.CheckResult;
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
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
        background().execute(() -> {
            CheckResult result = updater.check();
            Platform.runLater(() -> {
                checking = false;
                answer(result);
            });
        });
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
                    && !updater.isSkipped(available.release().version())) {
                Platform.runLater(() -> announce(available));
            }
        } catch (RuntimeException unexpected) {
            // One look that went wrong must not end the looks after it.
        }
    }

    /** A release the program found on its own: once a run, and only on a window that is shown. */
    private void announce(CheckResult.Available available) {
        if (showing || available.release().version().equals(declined)) {
            return;
        }
        if (owner != null && !owner.isShowing()) {
            if (whenShown == null) {
                whenShown = (property, was, shown) -> {
                    if (shown) {
                        owner.showingProperty().removeListener(whenShown);
                        whenShown = null;
                        Platform.runLater(() -> announce(available));
                    }
                };
                owner.showingProperty().addListener(whenShown);
            }
            return;
        }
        offer(available);
    }

    private void answer(CheckResult result) {
        switch (result) {
            case CheckResult.Available available -> offer(available);
            case CheckResult.UpToDate upToDate ->
                    message(Alert.AlertType.INFORMATION, texts.upToDate().formatted(upToDate.current()), "");
            case CheckResult.NotInstalled notInstalled ->
                    message(Alert.AlertType.INFORMATION, texts.notInstalled(), "");
            case CheckResult.Failed failed -> message(Alert.AlertType.WARNING, texts.checkFailed(), failed.reason());
        }
    }

    private void offer(CheckResult.Available available) {
        Release release = available.release();
        boolean installs = updater.canInstall();
        Optional<URI> page = release.page();
        ButtonType update = new ButtonType(installs ? texts.updateNow() : texts.openPage(), ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(texts.later(), ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType skip = new ButtonType(texts.skip(), ButtonBar.ButtonData.LEFT);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(texts.title());
        alert.setHeaderText(texts.available().formatted(release.version(), updater.appName()));
        VBox content = new VBox(8, wrapped(texts.current().formatted(available.current())));
        if (!release.notes().isBlank()) {
            TextArea notes = new TextArea(release.notes().strip());
            notes.setEditable(false);
            notes.setWrapText(true);
            notes.setPrefRowCount(8);
            notes.setPrefColumnCount(40);
            content.getChildren().addAll(new Label(texts.whatsNew()), notes);
        }
        if (installs) {
            content.getChildren().add(wrapped(texts.willRestart()));
        }
        alert.getDialogPane().setContent(content);
        if (installs || page.isPresent()) {
            alert.getButtonTypes().setAll(update, later, skip);
        } else {
            alert.getButtonTypes().setAll(later, skip);
        }
        dress(alert);

        showing = true;
        Optional<ButtonType> chosen = alert.showAndWait();
        showing = false;
        if (chosen.isPresent() && chosen.get() == update) {
            List<String> others = installs ? updater.runningAlongside() : List.of();
            if (!others.isEmpty()) {
                // The installer could not replace files these still hold; the offer comes back once told.
                message(Alert.AlertType.WARNING, texts.closeOthers(), String.join("\n", others));
                offer(available);
            } else if (installs) {
                download(release);
            } else {
                page.ifPresent(UpdatePrompt::open);
            }
        } else if (chosen.isPresent() && chosen.get() == skip) {
            updater.skip(release.version());
        } else {
            declined = release.version();
        }
    }

    private void download(Release release) {
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
                return;
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> failed(dialog, release, texts.downloadFailed(), failure));
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
                Platform.runLater(() -> failed(dialog, release, texts.installFailed(), failure));
                return;
            }
            Platform.runLater(() -> {
                stop();
                exit.run();
            });
        }, "update-download");
        worker.setDaemon(true);

        dialog.getDialogPane().lookupButton(cancel).addEventFilter(ActionEvent.ACTION, pressed -> worker.interrupt());
        dialog.setOnCloseRequest(closing -> worker.interrupt());
        dialog.setOnHidden(hidden -> showing = false);
        showing = true;
        dialog.show();
        worker.start();
    }

    private void failed(Dialog<?> progress, Release release, String what, Exception failure) {
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
    }

    private void message(Alert.AlertType type, String text, String detail) {
        Alert alert = new Alert(type);
        alert.setTitle(texts.title());
        alert.setHeaderText(text);
        alert.getDialogPane().setContent(detail.isBlank() ? null : wrapped(detail));
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

    /** Opens a web page in the person's browser. HTTPS only: the address came from the release. */
    private static void open(URI page) {
        if (!"https".equalsIgnoreCase(page.getScheme())) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("win")
                ? List.of("rundll32", "url.dll,FileProtocolHandler", page.toString())
                : List.of(os.contains("mac") ? "open" : "xdg-open", page.toString());
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
