package com.hamza.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadTest {

    private static final URI URL = URI.create("https://github.com/owner/app/releases/download/v2.5.0/App-setup.exe");
    private static final byte[] FILE = content(300_000);

    @TempDir
    Path folder;

    private final List<Long> asked = new ArrayList<>();

    private static byte[] content(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + i / 7);
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Release.Installer published() throws Exception {
        return new Release.Installer("App-setup.exe", URL, FILE.length, Optional.of(sha256(FILE)));
    }

    /** A server that honours a range. */
    private Download.Transfer ranged(byte[] served) {
        return (uri, from) -> {
            asked.add(from);
            if (from >= served.length) {
                return new Download.Opened(416, -1, InputStream.nullInputStream());
            }
            byte[] rest = Arrays.copyOfRange(served, (int) from, served.length);
            return new Download.Opened(from > 0 ? 206 : 200, from > 0 ? from : -1, new ByteArrayInputStream(rest));
        };
    }

    @Test
    void downloadsAndChecksTheFile() throws Exception {
        List<Long> progress = new ArrayList<>();
        Path file = new Download(ranged(FILE)).fetch(published(), folder, (done, total) -> progress.add(done));
        assertEquals(folder.resolve("App-setup.exe"), file);
        assertArrayEquals(FILE, Files.readAllBytes(file));
        assertFalse(Files.exists(folder.resolve("App-setup.exe.part")));
        assertEquals((long) FILE.length, progress.get(progress.size() - 1));
    }

    @Test
    void aFileAlreadyDownloadedIsNotAskedForAgain() throws Exception {
        Files.write(folder.resolve("App-setup.exe"), FILE);
        new Download(ranged(FILE)).fetch(published(), folder, Download.Progress.NONE);
        assertTrue(asked.isEmpty());
    }

    @Test
    void goesOnFromWhereAnEarlierAttemptStopped() throws Exception {
        Files.write(folder.resolve("App-setup.exe.part"), Arrays.copyOf(FILE, 100_000));
        Path file = new Download(ranged(FILE)).fetch(published(), folder, Download.Progress.NONE);
        assertEquals(List.of(100_000L), asked);
        assertArrayEquals(FILE, Files.readAllBytes(file));
    }

    @Test
    void aServerThatIgnoresTheRangeStartsThePartAgain() throws Exception {
        Files.write(folder.resolve("App-setup.exe.part"), Arrays.copyOf(FILE, 100_000));
        Download.Transfer whole = (uri, from) -> new Download.Opened(200, -1, new ByteArrayInputStream(FILE));
        assertArrayEquals(FILE, Files.readAllBytes(new Download(whole).fetch(published(), folder, Download.Progress.NONE)));
    }

    @Test
    void aStalePartIsThrownAwayWhenTheServerRefusesItsRange() throws Exception {
        Release.Installer unknownSize = new Release.Installer("App-setup.exe", URL, -1, Optional.of(sha256(FILE)));
        Files.write(folder.resolve("App-setup.exe.part"), content(400_000));
        Path file = new Download(ranged(FILE)).fetch(unknownSize, folder, Download.Progress.NONE);
        assertEquals(List.of(400_000L, 0L), asked);
        assertArrayEquals(FILE, Files.readAllBytes(file));
    }

    @Test
    void aFileThatIsNotThePublishedOneIsDeleted() throws Exception {
        byte[] tampered = FILE.clone();
        tampered[1234] ^= 1;
        Download download = new Download(ranged(tampered));
        IOException refused = assertThrows(IOException.class,
                () -> download.fetch(published(), folder, Download.Progress.NONE));
        assertTrue(refused.getMessage().contains("SHA-256"), refused.getMessage());
        try (var left = Files.list(folder)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void aDownloadCutShortIsKeptForTheNextAttempt() throws Exception {
        byte[] half = Arrays.copyOf(FILE, 150_000);
        Download.Transfer cut = (uri, from) -> new Download.Opened(200, -1, new ByteArrayInputStream(half));
        assertThrows(IOException.class, () -> new Download(cut).fetch(published(), folder, Download.Progress.NONE));
        assertEquals(150_000, Files.size(folder.resolve("App-setup.exe.part")));
        assertFalse(Files.exists(folder.resolve("App-setup.exe")));

        Path file = new Download(ranged(FILE)).fetch(published(), folder, Download.Progress.NONE);
        assertEquals(List.of(150_000L), asked);
        assertArrayEquals(FILE, Files.readAllBytes(file));
    }

    @Test
    void anErrorStatusIsAFailure() throws Exception {
        Download.Transfer missing = (uri, from) -> new Download.Opened(404, -1, InputStream.nullInputStream());
        assertThrows(IOException.class, () -> new Download(missing).fetch(published(), folder, Download.Progress.NONE));
    }

    @Test
    void installersOfOtherReleasesAreRemoved() throws Exception {
        Files.write(folder.resolve("App-2.3.0-setup.exe"), new byte[10]);
        Files.write(folder.resolve("App-2.2.0-setup.exe.part"), new byte[10]);
        Files.writeString(folder.resolve("state.properties"), "skipped=2.3.0");
        new Download(ranged(FILE)).fetch(published(), folder, Download.Progress.NONE);
        assertFalse(Files.exists(folder.resolve("App-2.3.0-setup.exe")));
        assertFalse(Files.exists(folder.resolve("App-2.2.0-setup.exe.part")));
        assertTrue(Files.exists(folder.resolve("state.properties")));
    }

    @Test
    void thePublishedNameNeverLeavesTheFolder() throws Exception {
        assertEquals("evil.exe", Download.safeName("..\\..\\Startup\\evil.exe"));
        assertEquals("evil.exe", Download.safeName("../../evil.exe"));
        assertEquals("Git Backup-0.2.0.msi", Download.safeName("Git Backup-0.2.0.msi"));
        assertEquals("a_b.exe", Download.safeName("a:b.exe"));
        assertThrows(IOException.class, () -> Download.safeName("script.ps1"));
        assertThrows(IOException.class, () -> Download.safeName(".exe"));
        assertThrows(IOException.class, () -> Download.safeName(""));
    }
}
