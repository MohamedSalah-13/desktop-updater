package com.hamza.updater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppUpdaterTest {

    @TempDir
    Path folder;

    private static Release release(String version) {
        return new Release(Version.of(version), "App " + version, "notes", Optional.empty(),
                new Release.Installer("App-setup.exe", URI.create("https://github.com/o/a/releases/download/x/App-setup.exe"),
                        10, Optional.empty()), false);
    }

    private AppUpdater updater(String current, ReleaseSource source) {
        return AppUpdater.builder("App").currentVersion(current).source(source).folder(folder).build();
    }

    @Test
    void aNewerReleaseIsAvailable() {
        CheckResult result = updater("2.4.0", () -> Optional.of(release("2.5.0"))).check();
        CheckResult.Available available = assertInstanceOf(CheckResult.Available.class, result);
        assertEquals(Version.of("2.5.0"), available.release().version());
        assertEquals(Version.of("2.4.0"), available.current());
    }

    @Test
    void theSameOrAnOlderReleaseIsUpToDate() {
        assertInstanceOf(CheckResult.UpToDate.class, updater("2.5.0", () -> Optional.of(release("2.5.0"))).check());
        assertInstanceOf(CheckResult.UpToDate.class, updater("2.6.0", () -> Optional.of(release("2.5.0"))).check());
        assertInstanceOf(CheckResult.UpToDate.class, updater("2.6.0", Optional::empty).check());
    }

    @Test
    void theInstallerThatDidItsWorkIsRemovedOnceUpToDate() throws IOException {
        Path used = Files.write(folder.resolve("App-2.5.0-setup.exe"), new byte[10]);
        Path state = Files.writeString(folder.resolve("state.properties"), "skipped=2.4.5");
        updater("2.4.0", () -> Optional.of(release("2.5.0"))).check();
        assertTrue(Files.exists(used), "kept while the release is still to be installed");
        updater("2.5.0", () -> Optional.of(release("2.5.0"))).check();
        assertFalse(Files.exists(used));
        assertTrue(Files.exists(state));
    }

    @Test
    void aCopyWithoutAVersionIsNeverToldOfARelease() {
        List<String> asked = new ArrayList<>();
        ReleaseSource source = () -> {
            asked.add("asked");
            return Optional.of(release("9.0.0"));
        };
        assertInstanceOf(CheckResult.NotInstalled.class, updater(null, source).check());
        assertInstanceOf(CheckResult.NotInstalled.class, updater("${project.version}", source).check());
        assertTrue(asked.isEmpty());
    }

    @Test
    void noAnswerIsAFailureAndNeverAnException() {
        CheckResult offline = updater("2.4.0", () -> {
            throw new IOException("no route to host");
        }).check();
        assertTrue(assertInstanceOf(CheckResult.Failed.class, offline).reason().contains("no route to host"));
        CheckResult broken = updater("2.4.0", () -> {
            throw new IllegalStateException("unexpected");
        }).check();
        assertInstanceOf(CheckResult.Failed.class, broken);
    }

    @Test
    void aSkippedReleaseIsRememberedUntilANewerOne() {
        AppUpdater updater = updater("2.4.0", Optional::empty);
        assertFalse(updater.isSkipped(Version.of("2.5.0")));
        updater.skip(Version.of("v2.5.0"));
        assertTrue(updater("2.4.0", Optional::empty).isSkipped(Version.of("2.5.0")));
        assertFalse(updater.isSkipped(Version.of("2.6.0")));
        assertTrue(Files.isRegularFile(folder.resolve("state.properties")));
    }

    @Test
    void installHandsTheInstallerToTheScript() throws IOException {
        Path installer = Files.write(folder.resolve("App-setup.exe"), new byte[10]);
        List<List<String>> started = new ArrayList<>();
        AppUpdater updater = AppUpdater.builder("App").currentVersion("2.4.0").source(Optional::empty).folder(folder)
                .installer(InstallerKind.INNO_SETUP).relaunch(folder.resolve("App.exe"))
                .launch(new InstallerLaunch(started::add)).build();
        if (!updater.canInstall()) {
            return;
        }
        updater.install(installer);
        assertEquals(1, started.size());
        assertEquals(folder.resolve("apply-update.ps1").toString(), started.get(0).get(started.get(0).size() - 1));
        String script = Files.readString(folder.resolve("apply-update.ps1"));
        assertTrue(script.startsWith("﻿"), "the script starts with a byte-order mark");
        assertTrue(script.contains("/SILENT /SUPPRESSMSGBOXES /NORESTART"));
        assertTrue(script.contains(installer.toString()));
    }

    @Test
    void theUpdatesFolderIsNamedAfterTheProgram() {
        assertTrue(AppUpdater.defaultFolder("Git Backup").endsWith("Git Backup-updates"));
        assertTrue(AppUpdater.defaultFolder("a/b:c").endsWith("a_b_c-updates"));
    }
}
