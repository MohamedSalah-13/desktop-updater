package com.hamza.updater;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs a downloaded installer over the running program.
 *
 * <p>The installer cannot replace files the program still holds, and the program cannot wait for an
 * installer that is replacing it. So a small PowerShell script is left in charge: it waits for the program
 * to close, runs the installer, and opens the program again. The program's part is to start the script and
 * close.
 *
 * <p><b>Two processes are waited for.</b> A {@code jpackage} launcher runs as two processes of one name -
 * the launcher, and a child of it that runs Java - and the launcher holds the program's {@code .exe} open
 * until the child ends. Both are named to the script.
 *
 * <p>The script is written as UTF-8 with a byte-order mark: Windows PowerShell 5.1 reads a script without
 * one in the system's code page, and a folder with an Arabic name would not survive that.
 */
public final class InstallerLaunch {

    /** How long the script waits for the program to close before it gives the update up. */
    static final int CLOSE_TIMEOUT_SECONDS = 120;

    @FunctionalInterface
    public interface Starter {
        void start(List<String> command) throws IOException;
    }

    private final Starter starter;

    public InstallerLaunch() {
        this(command -> new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start());
    }

    public InstallerLaunch(Starter starter) {
        this.starter = starter;
    }

    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Starts the script that installs {@code installer} once this program has closed. The caller closes the
     * program right after; if it is still running after {@value #CLOSE_TIMEOUT_SECONDS} seconds the script
     * gives up and nothing is installed.
     *
     * @param relaunch the program to open again after the installation; empty to open nothing
     * @param folder   where the script and its log are written
     */
    public void start(Path installer, InstallerKind kind, Optional<Path> relaunch, Path folder) throws IOException {
        if (!isSupported()) {
            throw new IOException("Installing an update is done on Windows only");
        }
        if (!Files.isRegularFile(installer)) {
            throw new IOException("The installer is not there: " + installer);
        }
        Files.createDirectories(folder);
        Path script = folder.resolve("apply-update.ps1");
        Path log = folder.resolve("update.log");
        String text = script(processesToWaitFor(), installer.toAbsolutePath(), kind,
                relaunch.map(Path::toAbsolutePath), log.toAbsolutePath());
        try (OutputStream out = Files.newOutputStream(script)) {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        starter.start(command(script));
    }

    static List<String> command(Path script) {
        String root = System.getenv("SystemRoot");
        String powershell = root == null || root.isBlank()
                ? "powershell.exe"
                : Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        return List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toAbsolutePath().toString());
    }

    /** This process, and the {@code jpackage} launcher above it when there is one. */
    static List<Long> processesToWaitFor() {
        ProcessHandle self = ProcessHandle.current();
        List<Long> processes = new ArrayList<>();
        processes.add(self.pid());
        String launcher = System.getProperty("jpackage.app-path");
        Optional<ProcessHandle> parent = self.parent();
        if (launcher != null && !launcher.isBlank() && parent.isPresent()) {
            Optional<String> parentCommand = parent.get().info().command();
            if (parentCommand.isPresent() && samePath(parentCommand.get(), launcher)) {
                processes.add(parent.get().pid());
            }
        }
        return processes;
    }

    /**
     * The other programs running out of this program's installed folder, by name - a second launcher of the
     * same installation (a server beside its client), or another window of this one. The installer cannot
     * replace files they hold, so the person closes them before an update is installed. Empty for a copy
     * no {@code jpackage} launcher started.
     */
    static List<String> runningAlongside() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher == null || launcher.isBlank()) {
            return List.of();
        }
        Path folder;
        try {
            folder = Path.of(launcher).toAbsolutePath().normalize().getParent();
        } catch (RuntimeException unreadable) {
            return List.of();
        }
        if (folder == null) {
            return List.of();
        }
        List<Long> own = processesToWaitFor();
        return ProcessHandle.allProcesses()
                .filter(process -> !own.contains(process.pid()))
                .map(process -> process.info().command().orElse(""))
                .filter(command -> inside(folder, command))
                .map(InstallerLaunch::programName)
                .distinct()
                .sorted()
                .toList();
    }

    /** Whether an executable lives in a folder or below it; Windows paths, so upper and lower case alike. */
    static boolean inside(Path folder, String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            String base = folder.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            String path = Path.of(command).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            return path.startsWith(base + java.io.File.separator);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** {@code C:\...\File Transfer Server.exe} as a person knows it: {@code File Transfer Server}. */
    static String programName(String command) {
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1);
        return name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static boolean samePath(String one, String other) {
        try {
            return Path.of(one).toAbsolutePath().normalize().toString()
                    .equalsIgnoreCase(Path.of(other).toAbsolutePath().normalize().toString());
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    static String script(List<Long> waitFor, Path installer, InstallerKind kind, Optional<Path> relaunch, Path log) {
        boolean msi = installer.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".msi");
        List<String> arguments = new ArrayList<>();
        if (msi) {
            arguments.add("/i");
            arguments.add(installer.toString());
        }
        arguments.addAll(kind.arguments());
        String commandLine = arguments.stream().map(InstallerLaunch::argument).collect(Collectors.joining(" "));
        String pids = waitFor.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return String.join("\r\n",
                "# Written by desktop-updater. Installs a downloaded update once the program has closed.",
                "$log = " + literal(log.toString()),
                "function Write-Log([string]$message) {",
                "    try {",
                "        Add-Content -LiteralPath $log -Encoding UTF8 -Value ('{0:yyyy-MM-dd HH:mm:ss} {1}' -f (Get-Date), $message)",
                "    } catch { }",
                "}",
                "$waitFor = @(" + pids + ")",
                "foreach ($id in $waitFor) {",
                "    try { Wait-Process -Id $id -Timeout " + CLOSE_TIMEOUT_SECONDS + " -ErrorAction Stop } catch { }",
                "}",
                "foreach ($id in $waitFor) {",
                "    if (Get-Process -Id $id -ErrorAction SilentlyContinue) {",
                "        Write-Log \"The program (process $id) is still running; the update was not installed.\"",
                "        exit 1",
                "    }",
                "}",
                "$installer = " + literal(msi ? "msiexec.exe" : installer.toString()),
                "$arguments = " + literal(commandLine),
                "Write-Log \"Starting $installer $arguments\"",
                "try {",
                "    if ($arguments) {",
                "        $process = Start-Process -FilePath $installer -ArgumentList $arguments -Wait -PassThru",
                "    } else {",
                "        $process = Start-Process -FilePath $installer -Wait -PassThru",
                "    }",
                "    Write-Log \"The installer ended with exit code $($process.ExitCode).\"",
                "} catch {",
                "    Write-Log \"The installer did not start: $($_.Exception.Message)\"",
                "}",
                "$program = " + literal(relaunch.map(Path::toString).orElse("")),
                "if ($program -and (Test-Path -LiteralPath $program)) {",
                "    Start-Process -FilePath $program",
                "}",
                "");
    }

    /** A Windows command-line argument: quoted when it holds a blank. */
    private static String argument(String value) {
        return value.isEmpty() || value.chars().anyMatch(Character::isWhitespace) ? '"' + value + '"' : value;
    }

    /** A PowerShell single-quoted string; PowerShell reads the curly quotes as quotes too. */
    static String literal(String value) {
        StringBuilder literal = new StringBuilder("'");
        for (char c : value.toCharArray()) {
            if (c == '\'' || c == '‘' || c == '’' || c == '‚' || c == '‛') {
                literal.append(c);
            }
            literal.append(c);
        }
        return literal.append('\'').toString();
    }
}
