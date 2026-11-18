package com.hamza.updater;

import com.hamza.updater.internal.Http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Downloads a release's installer and accepts it only when its size and SHA-256 are the published ones; a
 * file that does not match is deleted and never run.
 *
 * <p>A download cut short is kept beside the installer ({@code <name>.part}) and goes on from where it
 * stopped the next time. A server that ignores the range answers the whole file, and the part starts again.
 * Interrupting the thread stops the download between two reads.
 */
public final class Download {

    /** How far a download has come, for a window that shows it. */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (done, total) -> {
        };

        /** @param total the file's size; zero or less when it is not known */
        void at(long done, long total);
    }

    /** One request for the file, from a byte on. */
    @FunctionalInterface
    public interface Transfer {
        /** @param from the first byte wanted; zero for the whole file */
        Opened open(URI uri, long from) throws IOException;
    }

    /**
     * @param status     the HTTP status: 200 for the whole file, 206 for the part asked for
     * @param rangeStart the first byte of a 206 answer; -1 when the server did not say
     */
    public record Opened(int status, long rangeStart, InputStream body) implements Closeable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    private final Transfer transfer;

    public Download() {
        this((uri, from) -> {
            Http.Body body = Http.download(uri, from);
            return new Opened(body.status(), body.rangeStart(), body.stream());
        });
    }

    public Download(Transfer transfer) {
        this.transfer = transfer;
    }

    /**
     * The installer in {@code folder}: the one already there when it still matches, otherwise downloaded.
     * Installers of other releases left in the folder are removed.
     *
     * @throws InterruptedIOException when the thread was interrupted; what was received is kept
     * @throws IOException            when the download failed or the file is not the published one
     */
    public Path fetch(Release.Installer installer, Path folder, Progress progress) throws IOException {
        String name = safeName(installer.name());
        Files.createDirectories(folder);
        Path file = folder.resolve(name);
        Path part = folder.resolve(name + ".part");
        removeOthers(folder, file, part);

        if (Files.isRegularFile(file)) {
            if (matches(file, installer)) {
                progress.at(Files.size(file), Files.size(file));
                return file;
            }
            Files.delete(file);
        }
        long have = Files.isRegularFile(part) ? Files.size(part) : 0;
        if (installer.size() > 0 && have > installer.size()) {
            Files.delete(part);
            have = 0;
        }
        if (have == 0 || have != installer.size()) {
            receive(installer, part, have, progress);
        }
        if (!matches(part, installer)) {
            Files.deleteIfExists(part);
            throw new IOException("The downloaded file is not the published one (size or SHA-256 differs)");
        }
        try {
            Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notOnThisDisk) {
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private void receive(Release.Installer installer, Path part, long have, Progress progress) throws IOException {
        try (Opened opened = transfer.open(installer.url(), have)) {
            if (opened.status() == 416 && have > 0) {
                // The part is not a beginning of this file any more.
                Files.deleteIfExists(part);
            } else {
                copy(opened, installer, part, have, progress);
                return;
            }
        }
        try (Opened opened = transfer.open(installer.url(), 0)) {
            copy(opened, installer, part, 0, progress);
        }
    }

    private static void copy(Opened opened, Release.Installer installer, Path part, long have, Progress progress)
            throws IOException {
        boolean resumed = opened.status() == 206 && have > 0 && opened.rangeStart() == have;
        if (!resumed && opened.status() != 200) {
            throw new IOException("The download answered HTTP " + opened.status());
        }
        long done = resumed ? have : 0;
        long total = installer.size();
        StandardOpenOption mode = resumed ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)) {
            byte[] buffer = new byte[64 * 1024];
            long reported = done;
            progress.at(done, total);
            int read;
            while ((read = opened.body().read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("The download was cancelled");
                }
                out.write(buffer, 0, read);
                done += read;
                if (total > 0 && done > total) {
                    throw new IOException("The server sent more than the published " + total + " bytes");
                }
                if (done - reported >= 256 * 1024 || done == total) {
                    progress.at(done, total);
                    reported = done;
                }
            }
        }
        if (total > 0 && done < total) {
            // Kept: the next attempt goes on from here.
            throw new IOException("The download stopped at " + done + " of " + total + " bytes");
        }
    }

    private static boolean matches(Path candidate, Release.Installer installer) throws IOException {
        if (installer.size() > 0 && Files.size(candidate) != installer.size()) {
            return false;
        }
        return installer.sha256().isEmpty() || installer.sha256().get().equalsIgnoreCase(sha256(candidate));
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The installer's name as a plain file name in the folder: whatever a publisher's text says, it never
     * names a path, and it is an {@code .exe} or {@code .msi}.
     */
    static String safeName(String published) throws IOException {
        String name = published == null ? "" : published.strip();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1)
                .replaceAll("[^A-Za-z0-9._ ()-]", "_");
        String lower = name.toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || !(lower.endsWith(".exe") || lower.endsWith(".msi"))) {
            throw new IOException("Not an installer's name: " + published);
        }
        return name;
    }

    /**
     * Removes the installers left in the folder. Called once the program is up to date: the installer that
     * brought it there has done its work, and is tens of megabytes.
     */
    public void clear(Path folder) {
        if (Files.isDirectory(folder)) {
            removeOthers(folder, null, null);
        }
    }

    private static void removeOthers(Path folder, Path file, Path part) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.{exe,msi,part}")) {
            for (Path other : files) {
                if (!other.equals(file) && !other.equals(part)) {
                    Files.deleteIfExists(other);
                }
            }
        } catch (IOException leftBehind) {
            // An old installer that cannot be removed costs disk space and nothing else.
        }
    }
}
