package com.hamza.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The script that puts a zip of the program's folder in place, run for real by Windows PowerShell. The
 * "program" is a batch file that notes that it ran and ends with the exit code it was given.
 */
@EnabledOnOs(OS.WINDOWS)
class FolderSwapTest {

    @TempDir
    Path dir;

    private Path folder;
    private Path program;
    private Path updates;

    /** A batch file that adds its word to opened.txt beside the program's folder, and ends with the code. */
    private static String program(String word, int exit) {
        return "@echo " + word + ">>\"%~dp0..\\opened.txt\"\r\n@exit " + exit + "\r\n";
    }

    private void install(String oldWord) throws IOException {
        folder = Files.createDirectories(dir.resolve("My App"));
        program = folder.resolve("App.cmd");
        Files.writeString(program, program(oldWord, 0));
        Files.writeString(Files.createDirectories(folder.resolve("app")).resolve("held.txt"), "old");
        updates = dir.resolve("updates");
    }

    private Path release(String newWord, int exit) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("My App\\App.cmd", program(newWord, exit));
        entries.put("My App\\app\\held.txt", "new");
        return ZipFolderTest.zip(dir.resolve("App-2.5.0.zip"), entries);
    }

    /** Unpacks the release and runs the script to its end, as if the program had just closed. */
    private String swap(Path zip) throws Exception {
        Process closed = new ProcessBuilder("cmd", "/c", "exit").start();
        assertTrue(closed.waitFor(10, TimeUnit.SECONDS));
        ZipFolder.Swap swap = ZipFolder.unpack(zip, program);
        Files.createDirectories(updates);
        Path log = updates.resolve("update.log");
        Path script = updates.resolve("apply-update.ps1");
        InstallerLaunch.write(script, InstallerLaunch.folderScript(List.of(closed.pid()), swap, program, log));
        Process run = new ProcessBuilder(InstallerLaunch.command(script)).redirectErrorStream(true).start();
        String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(run.waitFor(90, TimeUnit.SECONDS), output);
        return Files.exists(log) ? Files.readString(log) : output;
    }

    /** What the programs noted, once the last one opened has had a moment to note it. */
    private List<String> opened(int expected) throws Exception {
        Path file = dir.resolve("opened.txt");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            lines = Files.exists(file) ? Files.readAllLines(file).stream().map(String::strip).toList() : List.of();
            if (lines.size() >= expected) {
                break;
            }
            Thread.sleep(100);
        }
        return lines;
    }

    @Test
    void theNewFolderTakesThePlaceOfTheOldAndOpens() throws Exception {
        install("old");
        String log = swap(release("new", 0));

        assertTrue(log.contains("The update is installed."), log);
        assertEquals("new", Files.readString(folder.resolve("app").resolve("held.txt")));
        assertEquals(List.of("new"), opened(1));
        assertFalse(Files.exists(dir.resolve("My App.update")));
        assertFalse(Files.exists(dir.resolve("My App.previous")));
    }

    @Test
    void aNewVersionThatEndsWithAnErrorGivesTheOldFolderBack() throws Exception {
        install("old");
        String log = swap(release("broken", 1));

        assertTrue(log.contains("ended with exit code 1"), log);
        assertTrue(log.contains("the previous one is put back"), log);
        assertEquals("old", Files.readString(folder.resolve("app").resolve("held.txt")));
        assertEquals(List.of("broken", "old"), opened(2));
        assertFalse(Files.exists(dir.resolve("My App.previous")));
        assertFalse(Files.exists(dir.resolve("My App.failed")));
    }

    @Test
    void aFolderSomethingStillHoldsIsLeftAsItWasAndOpens() throws Exception {
        install("old");
        Path zip = release("new", 0);
        String log;
        // java.io opens without sharing deletion, as a program holding its own files does.
        try (FileInputStream held = new FileInputStream(folder.resolve("app").resolve("held.txt").toFile())) {
            log = swap(zip);
        }

        assertTrue(log.contains("Could not move"), log);
        assertTrue(log.contains("The update was not installed"), log);
        assertEquals("old", Files.readString(folder.resolve("app").resolve("held.txt")));
        assertEquals(List.of("old"), opened(1));
        assertFalse(Files.exists(dir.resolve("My App.update")));
        assertFalse(Files.exists(dir.resolve("My App.previous")));
    }

    @Test
    void aZipIsUnpackedBeforeTheProgramIsAskedToClose() throws Exception {
        install("old");
        Path zip = release("new", 0);
        List<List<String>> started = new ArrayList<>();
        InstallerLaunch launch = new InstallerLaunch(started::add);

        launch.start(zip, InstallerKind.ZIP, Optional.of(program), updates);

        assertEquals(1, started.size());
        assertEquals("new", Files.readString(dir.resolve("My App.update").resolve("app").resolve("held.txt")));
        byte[] script = Files.readAllBytes(updates.resolve("apply-update.ps1"));
        assertEquals((byte) 0xEF, script[0]);
        assertTrue(new String(script, StandardCharsets.UTF_8).contains("Move-Folder $staged $target"));
        // Without a program to open again there is no folder to replace.
        assertThrows(IOException.class, () -> launch.start(zip, InstallerKind.ZIP, Optional.empty(), updates));
        assertEquals(1, started.size());
    }
}
