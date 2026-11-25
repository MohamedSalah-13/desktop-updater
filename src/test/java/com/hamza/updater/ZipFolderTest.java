package com.hamza.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipFolderTest {

    @TempDir
    Path dir;

    /** A zip with these entries; a name ending in a separator is a folder. */
    static Path zip(Path file, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private Path program() throws IOException {
        Path program = Files.createDirectories(dir.resolve("App")).resolve("App.exe");
        Files.writeString(program, "old");
        return program;
    }

    @Test
    void theFolderInTheZipIsUnpackedBesideTheProgramsFolder() throws IOException {
        // As Windows PowerShell's Compress-Archive writes it: backslashes, and the folder at the top.
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("App\\App.exe", "new");
        entries.put("App\\app\\App.cfg", "app.mainjar=$APPDIR\\app-2.5.0.jar");
        entries.put("App\\runtime\\legal\\", "");
        entries.put("App/runtime/release", "JAVA_VERSION=\"21\"");
        Path zip = zip(dir.resolve("App-2.5.0.zip"), entries);

        ZipFolder.Swap swap = ZipFolder.unpack(zip, program());

        assertEquals(dir.resolve("App"), swap.target());
        assertEquals(dir.resolve("App.update"), swap.staged());
        assertEquals(dir.resolve("App.previous"), swap.previous());
        assertEquals("new", Files.readString(swap.staged().resolve("App.exe")));
        assertEquals("app.mainjar=$APPDIR\\app-2.5.0.jar", Files.readString(swap.staged().resolve("app").resolve("App.cfg")));
        assertTrue(Files.isDirectory(swap.staged().resolve("runtime").resolve("legal")));
        assertTrue(Files.isRegularFile(swap.staged().resolve("runtime").resolve("release")));
        assertEquals("old", Files.readString(dir.resolve("App").resolve("App.exe")));
    }

    @Test
    void theLauncherMayBeAtTheTopOfTheZipAndAnEarlierUnpackingIsReplaced() throws IOException {
        Path program = program();
        Path leftover = Files.createDirectories(dir.resolve("App.update").resolve("stale"));
        Files.writeString(leftover.resolve("old.txt"), "from an update that never ran");
        Path zip = zip(dir.resolve("App.zip"), Map.of("app.EXE", "new", "app/App.cfg", "cfg"));

        ZipFolder.Swap swap = ZipFolder.unpack(zip, program);

        assertEquals("new", Files.readString(swap.staged().resolve("app.EXE")));
        assertTrue(Files.isRegularFile(swap.staged().resolve("app").resolve("App.cfg")));
        assertFalse(Files.exists(swap.staged().resolve("stale")));
    }

    @Test
    void aZipWithoutTheProgramsLauncherIsRefusedAndLeavesNothing() throws IOException {
        Path program = program();
        Path other = zip(dir.resolve("Other.zip"), Map.of("Other\\Other.exe", "x", "Other\\app\\x.jar", "x"));
        IOException refused = assertThrows(IOException.class, () -> ZipFolder.unpack(other, program));
        assertTrue(refused.getMessage().contains("does not hold App.exe"), refused.getMessage());
        // Deeper than one folder is not the program's folder either.
        Path deep = zip(dir.resolve("Deep.zip"), Map.of("a/b/App.exe", "x"));
        assertThrows(IOException.class, () -> ZipFolder.unpack(deep, program));
        Path twice = zip(dir.resolve("Twice.zip"), Map.of("App.exe", "x", "App/App.exe", "x"));
        assertThrows(IOException.class, () -> ZipFolder.unpack(twice, program));
        assertFalse(Files.exists(dir.resolve("App.update")));
    }

    @Test
    void aNameThatLeadsOutOfTheFolderIsRefusedAndLeavesNothing() throws IOException {
        Path program = program();
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("App\\App.exe", "new");
        entries.put("App\\..\\..\\Startup\\evil.cmd", "evil");
        Path zip = zip(dir.resolve("Evil.zip"), entries);

        IOException refused = assertThrows(IOException.class, () -> ZipFolder.unpack(zip, program));

        assertTrue(refused.getMessage().contains("outside its folder"), refused.getMessage());
        assertFalse(Files.exists(dir.resolve("App.update")));
        assertFalse(Files.exists(dir.getParent().resolve("Startup")));
    }

    @Test
    void aFileThatIsNotAZipIsRefused() throws IOException {
        Path program = program();
        Path broken = Files.writeString(dir.resolve("App.zip"), "not a zip at all");
        IOException refused = assertThrows(IOException.class, () -> ZipFolder.unpack(broken, program));
        assertFalse(Files.exists(dir.resolve("App.update")));
        assertTrue(refused.getMessage().contains("zip"), refused.getMessage());
    }
}
