package com.hamza.updater;

import com.hamza.updater.internal.Http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A program's updates: is there a newer release, download its installer, run it.
 *
 * <pre>{@code
 * AppUpdater updater = AppUpdater.builder("Git Backup")
 *         .source(ReleaseSource.github("owner/repository").asset("*-setup.exe"))
 *         .build();
 * }</pre>
 *
 * <p><b>The program's own version is not written anywhere by hand.</b> A program installed from a
 * {@code jpackage} installer is told its version by the launcher (the {@code jpackage.app-version} system
 * property), which is the version the installer was built with. A copy run from an IDE has none, and is
 * never told of a release - a developer's build is not something to install over.
 *
 * <p>Every method here blocks and none belongs on the JavaFX thread;
 * {@link com.hamza.updater.fx.UpdatePrompt} is the window that calls them from the background.
 */
public final class AppUpdater {

    private final String appName;
    private final Optional<Version> current;
    private final ReleaseSource source;
    private final InstallerKind installerKind;
    private final Path folder;
    private final Optional<Path> relaunch;
    private final Download download;
    private final InstallerLaunch launch;
    private final Supplier<List<String>> alongside;
    private final Optional<String> publicKey;
    private final SignatureFetch signatures;
    private final ReentrantLock downloading = new ReentrantLock();
    private final Os os;
    private final InstallerLaunch.Starter opener;

    /** One GET of a signature file, as text. */
    @FunctionalInterface
    public interface SignatureFetch {
        String fetch(URI address) throws IOException;
    }

    private AppUpdater(Builder builder) {
        this.os = builder.os;
        this.opener = builder.opener;
        this.publicKey = builder.publicKey;
        this.signatures = builder.signatures;
        this.alongside = builder.alongside;
        this.appName = builder.appName;
        this.current = builder.current;
        this.source = Objects.requireNonNull(builder.source, "source");
        this.installerKind = builder.installerKind;
        this.folder = builder.folder != null ? builder.folder : defaultFolder(builder.appName);
        this.relaunch = builder.relaunch;
        this.download = builder.download;
        this.launch = builder.launch;
    }

    /** @param appName the program's name, as a person knows it; it names the window and the updates folder */
    public static Builder builder(String appName) {
        return new Builder(appName);
    }

    public String appName() {
        return appName;
    }

    /** The running program's version; empty for a copy nobody installed. */
    public Optional<Version> currentVersion() {
        return current;
    }

    /** Looks for a newer release. Never throws: no connection is a {@link CheckResult.Failed}. */
    public CheckResult check() {
        if (current.isEmpty()) {
            return new CheckResult.NotInstalled();
        }
        try {
            Optional<Release> latest = source.latest();
            if (latest.isPresent() && latest.get().version().isNewerThan(current.get())) {
                Optional<String> refusal = publicKey.isPresent() ? unsigned(latest.get()) : Optional.empty();
                if (refusal.isPresent()) {
                    return new CheckResult.Failed(refusal.get());
                }
                return new CheckResult.Available(latest.get(), current.get(),
                        latest.get().isRequiredFor(current.get()));
            }
            download.clear(folder);
            return new CheckResult.UpToDate(current.get());
        } catch (IOException | RuntimeException failure) {
            return new CheckResult.Failed(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    /**
     * Why a release is not the publisher's, for a program that carries the publisher's key; empty when its
     * signature holds. The signature covers the published SHA-256 and size, and the download is checked
     * against those same two, so a release that passes here cannot arrive as another file.
     */
    private Optional<String> unsigned(Release release) throws IOException {
        Release.Installer installer = release.installer();
        String name = "Release " + release.version();
        if (installer.sha256().isEmpty() || installer.size() <= 0) {
            return Optional.of(name + " publishes no SHA-256 or size of its installer, so its signature cannot be checked");
        }
        String signature;
        if (installer.signature().isPresent()) {
            signature = installer.signature().get();
        } else if (installer.signatureUrl().isPresent()) {
            signature = signatures.fetch(installer.signatureUrl().get());
        } else {
            return Optional.of(name + " is not signed; this program installs only releases signed by its publisher");
        }
        // A signature is under a hundred characters; anything long is not one.
        if (signature.length() > 512 || !ReleaseSignature.verify(publicKey.get(), signature, appName,
                release.version().toString(), installer.sha256().get(), installer.size())) {
            return Optional.of("The signature of " + name.toLowerCase(java.util.Locale.ROOT)
                    + " is not the publisher's; it is not offered");
        }
        return Optional.empty();
    }

    /**
     * Downloads the release's installer and checks it against the published size and SHA-256.
     *
     * @return the installer, ready for {@link #install}
     */
    public Path download(Release release, Download.Progress progress) throws IOException {
        // One download at a time: a second caller - the person pressing "Update now" while the release is
        // being fetched in the background - waits, and then finds the file already there.
        try {
            downloading.lockInterruptibly();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("The download was cancelled");
        }
        try {
            return download.fetch(release.installer(), folder, progress);
        } finally {
            downloading.unlock();
        }
    }

    /** Whether this computer can run the installer itself, close the program and open it again: Windows. */
    public boolean canInstall() {
        return os == Os.WINDOWS;
    }

    /**
     * Whether this computer can at least be handed the downloaded installer to open: Linux and macOS. There
     * the person finishes the installation - the package installer asks for a password, a disk image waits
     * for the program to be dragged - and starts the program again.
     */
    public boolean canOpen() {
        return os == Os.LINUX || os == Os.MAC;
    }

    /**
     * Hands a downloaded installer to the system to open, the way double-clicking it would. The program
     * keeps running.
     */
    public void open(Path installer) throws IOException {
        if (!canOpen()) {
            throw new IOException("Opening an installer is for Linux and macOS; on Windows it is installed");
        }
        if (!Files.isRegularFile(installer)) {
            throw new IOException("The installer is not there: " + installer);
        }
        opener.start(openCommand(os, installer));
    }

    static List<String> openCommand(Os os, Path installer) {
        return List.of(os == Os.MAC ? "open" : "xdg-open", installer.toAbsolutePath().toString());
    }

    /**
     * The other programs running out of this program's installed folder - a second launcher of the same
     * installation, or another window of this one. The installer cannot replace files they hold: ask the
     * person to close them before {@link #install}. Empty when nothing stands in the way.
     */
    public List<String> runningAlongside() {
        return alongside.get();
    }

    /**
     * Hands the installer over to run once the program has closed. <b>Close the program right after this
     * returns</b>; the installer waits for it, and opens the program again when it is done.
     */
    public void install(Path installer) throws IOException {
        launch.start(installer, installerKind, relaunch, folder);
    }

    /** Whether the person asked not to be told of this release again. */
    public boolean isSkipped(Version version) {
        return version.toString().equals(state().getProperty("skipped"));
    }

    /** Remembers that the person does not want this release; a newer one is announced as usual. */
    public void skip(Version version) {
        Properties state = state();
        state.setProperty("skipped", version.toString());
        // An installer fetched ahead of the question is of no use to somebody who said no.
        download.clear(folder);
        try {
            Files.createDirectories(folder);
            try (OutputStream out = Files.newOutputStream(stateFile())) {
                state.store(out, null);
            }
        } catch (IOException notSaved) {
            // The release is announced again at the next start, which is all that is lost.
        }
    }

    private Properties state() {
        Properties state = new Properties();
        if (Files.isRegularFile(stateFile())) {
            try (InputStream in = Files.newInputStream(stateFile())) {
                state.load(in);
            } catch (IOException | IllegalArgumentException unreadable) {
                // Read as nothing remembered.
            }
        }
        return state;
    }

    private Path stateFile() {
        return folder.resolve("state.properties");
    }

    /** Beside the program's own data, and outside the folder the installer replaces. */
    static Path defaultFolder(String appName) {
        String name = appName.replaceAll("[^\\p{L}\\p{N}._ -]", "_").strip() + "-updates";
        String local = System.getenv("LOCALAPPDATA");
        Path base = local != null && !local.isBlank()
                ? Path.of(local)
                : Path.of(System.getProperty("user.home"), ".cache");
        return base.resolve(name);
    }

    public static final class Builder {

        private final String appName;
        private Optional<Version> current = Version.parse(System.getProperty("jpackage.app-version"));
        private ReleaseSource source;
        private InstallerKind installerKind = InstallerKind.JPACKAGE;
        private Path folder;
        private Optional<Path> relaunch = launcher();
        private Download download = new Download();
        private InstallerLaunch launch = new InstallerLaunch();
        private Supplier<List<String>> alongside = InstallerLaunch::runningAlongside;
        private Optional<String> publicKey = Optional.empty();
        private Os os = Os.current();
        private InstallerLaunch.Starter opener = command -> new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        private SignatureFetch signatures = address -> {
            Http.Text answer = Http.get(address, Map.of("Accept", "application/octet-stream"));
            if (answer.status() != 200) {
                throw new IOException("The release's signature answered HTTP " + answer.status());
            }
            return answer.body().strip();
        };

        private Builder(String appName) {
            if (appName == null || appName.isBlank()) {
                throw new IllegalArgumentException("The program needs a name");
            }
            this.appName = appName.strip();
        }

        /** Where releases are published. Required. */
        public Builder source(ReleaseSource source) {
            this.source = source;
            return this;
        }

        /**
         * The running program's version, for a program that is not started by a {@code jpackage} launcher.
         * A text that is not a version leaves the program without one, and it is never told of a release.
         */
        public Builder currentVersion(String version) {
            this.current = Version.parse(version);
            return this;
        }

        /** What made the installer. {@link InstallerKind#JPACKAGE} unless said otherwise. */
        public Builder installer(InstallerKind kind) {
            this.installerKind = Objects.requireNonNull(kind);
            return this;
        }

        /** Where installers are downloaded to. {@code %LOCALAPPDATA%\<name>-updates} unless said otherwise. */
        public Builder folder(Path folder) {
            this.folder = folder;
            return this;
        }

        /**
         * The program to open after the installation. The {@code jpackage} launcher that started this copy
         * unless said otherwise; null to open nothing.
         */
        public Builder relaunch(Path program) {
            this.relaunch = Optional.ofNullable(program);
            return this;
        }

        /** Another downloader, for a test. */
        public Builder download(Download download) {
            this.download = Objects.requireNonNull(download);
            return this;
        }

        /** Another way to start the installer, for a test. */
        public Builder launch(InstallerLaunch launch) {
            this.launch = Objects.requireNonNull(launch);
            return this;
        }

        /**
         * The publisher's public key, as {@code ReleaseSignature keygen} printed it. With it the program is
         * offered only releases that carry the publisher's {@link ReleaseSignature}: a release put up by
         * somebody who took over the place releases are published - without the private key - is ignored.
         *
         * <p>From the first version that says this on, every release has to be signed, or installed copies
         * are offered nothing.
         *
         * @throws IllegalArgumentException when the text is not such a key
         */
        public Builder signedBy(String publicKey) {
            if (publicKey == null || !ReleaseSignature.isPublicKey(publicKey)) {
                throw new IllegalArgumentException("Not a public key from ReleaseSignature keygen");
            }
            this.publicKey = Optional.of(publicKey.strip());
            return this;
        }

        /** As if run on another system, with another way to open an installer there; for a test. */
        public Builder system(Os os, InstallerLaunch.Starter opener) {
            this.os = Objects.requireNonNull(os);
            this.opener = Objects.requireNonNull(opener);
            return this;
        }

        /** Another way to fetch a release's signature, for a test. */
        public Builder signatures(SignatureFetch signatures) {
            this.signatures = Objects.requireNonNull(signatures);
            return this;
        }

        /** Another way to find the programs running beside this one, for a test. */
        public Builder alongside(Supplier<List<String>> alongside) {
            this.alongside = Objects.requireNonNull(alongside);
            return this;
        }

        public AppUpdater build() {
            return new AppUpdater(this);
        }

        private static Optional<Path> launcher() {
            String path = System.getProperty("jpackage.app-path");
            try {
                return path == null || path.isBlank() ? Optional.empty() : Optional.of(Path.of(path));
            } catch (RuntimeException unreadable) {
                return Optional.empty();
            }
        }
    }
}
