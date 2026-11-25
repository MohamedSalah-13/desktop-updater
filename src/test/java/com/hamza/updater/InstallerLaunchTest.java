package com.hamza.updater;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerLaunchTest {

    private static final Path LOG = Path.of("updates", "update.log");

    @Test
    void waitsForBothProcessesThenInstallsThenReopens() {
        Path installer = Path.of("updates", "App-2.5.0-setup.exe");
        Path program = Path.of("App", "App.exe");
        String script = InstallerLaunch.script(List.of(4100L, 4096L), installer, InstallerKind.JPACKAGE,
                Optional.of(program), LOG);
        assertTrue(script.contains("$waitFor = @(4100, 4096)"));
        assertTrue(script.contains("$installer = '" + installer + "'"));
        assertTrue(script.contains("$arguments = '/passive /norestart'"));
        assertTrue(script.contains("$program = '" + program + "'"));
        int wait = script.indexOf("Wait-Process");
        int install = script.indexOf("Start-Process -FilePath $installer");
        int reopen = script.indexOf("Start-Process -FilePath $program");
        assertTrue(0 < wait && wait < install && install < reopen);
    }

    @Test
    void aZipSwapsTheFoldersOnceTheProgramHasClosedAndSwapsThemBackWhenItDoesNotStart() {
        Path folder = Path.of("C:\\Apps\\O'Brien App");
        ZipFolder.Swap swap = new ZipFolder.Swap(folder, Path.of(folder + ".update"), Path.of(folder + ".previous"));
        String script = InstallerLaunch.folderScript(List.of(4100L, 4096L), swap, folder.resolve("App.exe"), LOG);
        assertTrue(script.contains("$waitFor = @(4100, 4096)"));
        assertTrue(script.contains("$target = 'C:\\Apps\\O''Brien App'"));
        assertTrue(script.contains("$staged = 'C:\\Apps\\O''Brien App.update'"));
        assertTrue(script.contains("$previous = 'C:\\Apps\\O''Brien App.previous'"));
        assertTrue(script.contains("$program = 'C:\\Apps\\O''Brien App\\App.exe'"));
        assertTrue(script.contains("WaitForExit(" + InstallerLaunch.START_SECONDS * 1000 + ")"));
        int wait = script.indexOf("Wait-Process");
        int giveUp = script.indexOf("still running");
        int dropStaged = script.indexOf("Remove-Item -LiteralPath 'C:\\Apps\\O''Brien App.update'");
        int aside = script.indexOf("Move-Folder $target $previous");
        int in = script.indexOf("Move-Folder $staged $target");
        int open = script.indexOf("Test-Started (Start-Program)");
        int back = script.indexOf("Move-Folder $previous $target", open);
        assertTrue(0 < wait && wait < giveUp && giveUp < dropStaged && dropStaged < aside && aside < in
                && in < open && open < back, script);
        assertFalse(script.contains("msiexec"));
    }

    @Test
    void anMsiRunsThroughWindowsInstaller() {
        Path installer = Path.of("my updates", "App 2.5.0.msi");
        String script = InstallerLaunch.script(List.of(1L), installer, InstallerKind.JPACKAGE, Optional.empty(), LOG);
        assertTrue(script.contains("$installer = 'msiexec.exe'"));
        assertTrue(script.contains("$arguments = '/i \"" + installer + "\" /passive /norestart'"));
        assertTrue(script.contains("$program = ''"));
    }

    @Test
    void theWizardGetsNoArguments() {
        String script = InstallerLaunch.script(List.of(1L), Path.of("a.exe"), InstallerKind.WIZARD, Optional.empty(), LOG);
        assertTrue(script.contains("$arguments = ''"));
    }

    @Test
    void aQuoteInAPathCannotEndTheString() {
        assertEquals("'C:\\Users\\O''Brien\\a.exe'", InstallerLaunch.literal("C:\\Users\\O'Brien\\a.exe"));
        assertEquals("'a\u2019\u2019b'", InstallerLaunch.literal("a\u2019b"));
        String script = InstallerLaunch.script(List.of(1L), Path.of("O'Brien", "a.exe"), InstallerKind.WIZARD,
                Optional.empty(), LOG);
        assertFalse(script.contains("'O'Brien"));
    }

    @Test
    void powershellRunsTheScriptHiddenAndWithoutAProfile() {
        List<String> command = InstallerLaunch.command(Path.of("updates", "apply-update.ps1"));
        assertTrue(command.get(0).toLowerCase().endsWith("powershell.exe"));
        assertTrue(command.containsAll(List.of("-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File")));
        assertTrue(command.get(command.size() - 1).endsWith("apply-update.ps1"));
    }

    @Test
    void aProgramFromTheSameInstalledFolderStandsInTheWay() {
        Path folder = Path.of("apps", "File Transfer").toAbsolutePath();
        assertTrue(InstallerLaunch.inside(folder, folder.resolve("File Transfer Server.exe").toString()));
        assertTrue(InstallerLaunch.inside(folder, folder.resolve("runtime").resolve("bin").resolve("keytool.exe").toString()));
        assertTrue(InstallerLaunch.inside(folder, folder.toString().toUpperCase() + java.io.File.separator + "a.exe"));
        assertFalse(InstallerLaunch.inside(folder, Path.of("apps", "File Transfer Tools", "a.exe").toAbsolutePath().toString()));
        assertFalse(InstallerLaunch.inside(folder, Path.of("apps", "Other", "a.exe").toAbsolutePath().toString()));
        assertFalse(InstallerLaunch.inside(folder, ""));
        assertEquals("File Transfer Server", InstallerLaunch.programName("C:\\Apps\\File Transfer\\File Transfer Server.exe"));
        assertEquals("keytool", InstallerLaunch.programName("C:/Apps/runtime/bin/keytool.EXE"));
    }

    @Test
    void aCopyNoLauncherStartedHasNothingBesideIt() {
        assertTrue(InstallerLaunch.runningAlongside().isEmpty());
    }

    @Test
    void thisProcessIsAlwaysWaitedFor() {
        assertTrue(InstallerLaunch.processesToWaitFor().contains(ProcessHandle.current().pid()));
    }
}
