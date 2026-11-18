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
    void thisProcessIsAlwaysWaitedFor() {
        assertTrue(InstallerLaunch.processesToWaitFor().contains(ProcessHandle.current().pid()));
    }
}
