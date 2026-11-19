package com.hamza.updater;

import com.hamza.updater.internal.Http;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OsTest {

    @TempDir
    Path folder;

    @Test
    void theSystemIsReadFromItsName() {
        assertEquals(Os.WINDOWS, Os.of("Windows 11"));
        assertEquals(Os.WINDOWS, Os.of("Windows Server 2022"));
        assertEquals(Os.MAC, Os.of("Mac OS X"));
        assertEquals(Os.MAC, Os.of("Darwin"));
        assertEquals(Os.LINUX, Os.of("Linux"));
        assertEquals(Os.OTHER, Os.of("FreeBSD"));
        assertEquals(Os.OTHER, Os.of(null));
    }

    @Test
    void theProcessorIsNamedAsReleaseFilesNameIt() {
        assertEquals("x64", Os.archOf("amd64"));
        assertEquals("x64", Os.archOf("x86_64"));
        assertEquals("arm64", Os.archOf("aarch64"));
        assertEquals("arm64", Os.archOf("arm64"));
        assertEquals("riscv64", Os.archOf("riscv64"));
        assertEquals("", Os.archOf(null));
    }

    // ------------------------------------------------------------- the right file for the system

    private static String asset(String tag, String name) {
        return """
                {"name": "%s", "state": "uploaded", "size": 1000,
                 "browser_download_url": "https://github.com/owner/app/releases/download/%s/%s"}""".formatted(name, tag, name);
    }

    private static String release(String tag, String... names) {
        List<String> files = new ArrayList<>();
        for (String name : names) {
            files.add(asset(tag, name));
        }
        return """
                {"tag_name": "%s", "name": "App %s", "body": "", "draft": false, "prerelease": false,
                 "html_url": "https://github.com/owner/app/releases/tag/%s", "assets": [%s]}"""
                .formatted(tag, tag, tag, String.join(",", files));
    }

    private static GitHubReleases published(String... releases) {
        return ReleaseSource.github("owner/app")
                .asset("*-setup.exe")
                .assetFor(Os.LINUX, "*-linux-{arch}.deb")
                .assetFor(Os.MAC, "*-macos-{arch}.dmg")
                .fetchingWith((uri, headers) -> new Http.Text(200, "[" + String.join(",", releases) + "]"));
    }

    private static final String EVERYTHING = release("v0.3.0", "App-0.3.0-setup.exe", "App-0.3.0-linux-x64.deb",
            "App-0.3.0-macos-arm64.dmg", "App-0.3.0-macos-x64.dmg");

    @Test
    void eachSystemIsOfferedItsOwnInstaller() throws IOException {
        assertEquals("App-0.3.0-setup.exe", published(EVERYTHING).onSystem(Os.WINDOWS, "x64").latest().orElseThrow().installer().name());
        assertEquals("App-0.3.0-linux-x64.deb", published(EVERYTHING).onSystem(Os.LINUX, "x64").latest().orElseThrow().installer().name());
        assertEquals("App-0.3.0-macos-arm64.dmg", published(EVERYTHING).onSystem(Os.MAC, "arm64").latest().orElseThrow().installer().name());
        assertEquals("App-0.3.0-macos-x64.dmg", published(EVERYTHING).onSystem(Os.MAC, "x64").latest().orElseThrow().installer().name());
    }

    @Test
    void aReleaseWithoutAFileForThisSystemIsPassedOverThere() throws IOException {
        // The Windows installer goes up first; the others are added to the release minutes later, or never.
        String windowsOnly = release("v0.3.0", "App-0.3.0-setup.exe");
        String older = release("v0.2.0", "App-0.2.0-setup.exe", "App-0.2.0-linux-x64.deb");
        assertEquals(Version.of("0.3.0"), published(windowsOnly, older).onSystem(Os.WINDOWS, "x64").latest().orElseThrow().version());
        assertEquals(Version.of("0.2.0"), published(windowsOnly, older).onSystem(Os.LINUX, "x64").latest().orElseThrow().version());
        assertTrue(published(windowsOnly, older).onSystem(Os.LINUX, "arm64").latest().isEmpty(), "no build for this processor");
        assertTrue(published(windowsOnly, older).onSystem(Os.MAC, "arm64").latest().isEmpty());
    }

    @Test
    void aSystemNobodyNamedFallsBackToTheGeneralPattern() throws IOException {
        GitHubReleases onlyGeneral = ReleaseSource.github("owner/app").asset("*-setup.exe")
                .fetchingWith((uri, headers) -> new Http.Text(200, "[" + EVERYTHING + "]"));
        assertEquals("App-0.3.0-setup.exe", onlyGeneral.onSystem(Os.LINUX, "x64").latest().orElseThrow().installer().name());
        assertEquals("App-0.3.0-setup.exe", published(EVERYTHING).onSystem(Os.OTHER, "x64").latest().orElseThrow().installer().name());
    }

    // ------------------------------------------------------------------ what a system does with it

    private AppUpdater on(Os os, List<List<String>> opened) {
        return AppUpdater.builder("App").currentVersion("1.0.0").source(Optional::empty).folder(folder)
                .system(os, opened::add).build();
    }

    @Test
    void windowsInstallsLinuxAndMacOpen() {
        List<List<String>> opened = new ArrayList<>();
        assertTrue(on(Os.WINDOWS, opened).canInstall());
        assertFalse(on(Os.WINDOWS, opened).canOpen());
        for (Os os : List.of(Os.LINUX, Os.MAC)) {
            assertFalse(on(os, opened).canInstall());
            assertTrue(on(os, opened).canOpen());
        }
        assertFalse(on(Os.OTHER, opened).canInstall());
        assertFalse(on(Os.OTHER, opened).canOpen());
    }

    @Test
    void theInstallerIsHandedToTheSystemsOwnOpener() throws IOException {
        Path deb = Files.write(folder.resolve("App-0.3.0-linux-x64.deb"), new byte[10]);
        Path dmg = Files.write(folder.resolve("App 0.3.0 macos arm64.dmg"), new byte[10]);
        List<List<String>> opened = new ArrayList<>();
        on(Os.LINUX, opened).open(deb);
        on(Os.MAC, opened).open(dmg);
        assertEquals(List.of(List.of("xdg-open", deb.toAbsolutePath().toString()), List.of("open", dmg.toAbsolutePath().toString())), opened);

        assertThrows(IOException.class, () -> on(Os.WINDOWS, opened).open(deb), "on Windows it is installed, not opened");
        assertThrows(IOException.class, () -> on(Os.LINUX, opened).open(folder.resolve("missing.deb")));
        assertEquals(2, opened.size());
    }

    @Test
    void linuxAndMacInstallersAreInstallersToo() throws IOException {
        assertEquals("App-0.3.0-linux-x64.deb", Download.safeName("App-0.3.0-linux-x64.deb"));
        assertEquals("App-0.3.0.rpm", Download.safeName("App-0.3.0.rpm"));
        assertEquals("App-0.3.0-macos-arm64.dmg", Download.safeName("../App-0.3.0-macos-arm64.dmg"));
        assertEquals("App.pkg", Download.safeName("App.pkg"));
        assertThrows(IOException.class, () -> Download.safeName("install.sh"));
        assertThrows(IOException.class, () -> Download.safeName("App.tar.gz"));
    }
}
