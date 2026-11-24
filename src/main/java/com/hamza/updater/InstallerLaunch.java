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
 * <p>A zip of the program's folder ({@link InstallerKind#ZIP}) is unpacked beside the folder first, while the
 * program runs; the script then swaps the two folders and opens the program, and swaps them back when the new
 * one does not start.
 *
 * <p>The script is written as UTF-8 with a byte-order mark: Windows PowerShell 5.1 reads a script without
 * one in the system's code page, and a folder with an Arabic name would not survive that.
 */
public final class InstallerLaunch {

    /** How long the script waits for the program to close before it gives the update up. */
    static final int CLOSE_TIMEOUT_SECONDS = 120;

    /**
     * How long a program opened from a new folder has to end with an error before the old folder is put
     * back. One still running then, or one that ended without an error, works.
     */
    static final int START_SECONDS = 30;

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
        return Os.current() == Os.WINDOWS;
    }

    /**
     * Starts the script that installs {@code installer} once this program has closed. The caller closes the
     * program right after; if it is still running after {@value #CLOSE_TIMEOUT_SECONDS} seconds the script
     * gives up and nothing is installed.
     *
     * @param relaunch the program to open again after the installation; empty to open nothing. For a
     *                 {@link InstallerKind#ZIP} it is required, and its folder is the one replaced.
     * @param folder   where the script and its log are written
     * @throws IOException for a zip, also when it is not the program's folder or cannot be unpacked beside
     *                     it; the program has not been touched then
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
        String text;
        if (kind.replacesFolder()) {
            Path program = relaunch.orElseThrow(() -> new IOException(
                    "A zip replaces the folder of the program to open again, and this copy names none"))
                    .toAbsolutePath().normalize();
            ZipFolder.Swap swap = ZipFolder.unpack(installer, program);
            text = folderScript(processesToWaitFor(), swap, program, log.toAbsolutePath());
        } else {
            text = script(processesToWaitFor(), installer.toAbsolutePath(), kind,
                    relaunch.map(Path::toAbsolutePath), log.toAbsolutePath());
        }
        write(script, text);
        starter.start(command(script));
    }

    /** With a byte-order mark, or Windows PowerShell reads it in the system's code page. */
    static void write(Path script, String text) throws IOException {
        try (OutputStream out = Files.newOutputStream(script)) {
            out.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
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
        List<String> lines = waiting("Installs a downloaded update once the program has closed.", waitFor, log);
        lines.addAll(List.of(
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
                ""));
        return String.join("\r\n", lines);
    }

    /**
     * Puts the unpacked folder in the program's place: the program's folder is renamed aside, the new one
     * renamed to its name, and the program opened. Renamed, not copied, so the folder is never half one
     * version and half the other, and when the second rename fails the first is undone. A program that ends
     * with an error within {@value #START_SECONDS} seconds gets its old folder back.
     */
    static String folderScript(List<Long> waitFor, ZipFolder.Swap swap, Path program, Path log) {
        List<String> lines = waiting("Puts a downloaded copy of the program's folder in place once the program has closed.",
                waitFor, log, "Remove-Item -LiteralPath " + literal(swap.staged().toString())
                        + " -Recurse -Force -ErrorAction SilentlyContinue");
        lines.addAll(List.of(
                "$target = " + literal(swap.target().toString()),
                "$staged = " + literal(swap.staged().toString()),
                "$previous = " + literal(swap.previous().toString()),
                "$failed = $target + '.failed'",
                "$program = " + literal(program.toString()),
                "# Right after the program ends, Windows or an antivirus may hold one of its files for a moment.",
                "function Move-Folder([string]$from, [string]$to) {",
                "    $reason = ''",
                "    for ($try = 0; $try -lt 20; $try++) {",
                "        try {",
                "            Move-Item -LiteralPath $from -Destination $to -ErrorAction Stop",
                "            return $true",
                "        } catch {",
                "            $reason = $_.Exception.Message",
                "            Start-Sleep -Milliseconds 500",
                "        }",
                "    }",
                "    Write-Log \"Could not move $from to ${to}: $reason\"",
                "    return $false",
                "}",
                "function Remove-Folder([string]$folder) {",
                "    if (Test-Path -LiteralPath $folder) {",
                "        try { Remove-Item -LiteralPath $folder -Recurse -Force -ErrorAction Stop }",
                "        catch { Write-Log \"Could not remove ${folder}: $($_.Exception.Message)\" }",
                "    }",
                "}",
                "function Start-Program {",
                "    try {",
                "        $process = Start-Process -FilePath $program -PassThru -ErrorAction Stop",
                "        # Read now, or Windows PowerShell cannot tell the exit code later.",
                "        $null = $process.Handle",
                "        return $process",
                "    } catch {",
                "        Write-Log \"The program did not start: $($_.Exception.Message)\"",
                "        return $null",
                "    }",
                "}",
                "function Test-Started($process) {",
                "    if (-not $process) { return $false }",
                "    if (-not $process.WaitForExit(" + START_SECONDS * 1000 + ")) { return $true }",
                "    Write-Log \"The program ended with exit code $($process.ExitCode).\"",
                "    return $process.ExitCode -eq 0",
                "}",
                "Write-Log \"Replacing $target with $staged\"",
                "Remove-Folder $previous",
                "$swapped = $false",
                "if (-not (Test-Path -LiteralPath $previous) -and (Move-Folder $target $previous)) {",
                "    if (Move-Folder $staged $target) {",
                "        $swapped = $true",
                "    } elseif (-not (Move-Folder $previous $target)) {",
                "        Write-Log \"The program's folder is left at $previous; rename it back to $target.\"",
                "        exit 1",
                "    }",
                "}",
                "if (-not $swapped) {",
                "    Remove-Folder $staged",
                "    Write-Log 'The update was not installed; the program opens as it was.'",
                "    $null = Start-Program",
                "    exit 1",
                "}",
                "Write-Log \"Replaced $target.\"",
                "if (Test-Started (Start-Program)) {",
                "    Remove-Folder $previous",
                "    Write-Log 'The update is installed.'",
                "    exit 0",
                "}",
                "Write-Log 'The new version did not start; the previous one is put back.'",
                "Remove-Folder $failed",
                "if ((Move-Folder $target $failed) -and (Move-Folder $previous $target)) {",
                "    Remove-Folder $failed",
                "    $null = Start-Program",
                "} else {",
                "    Write-Log \"Put it back by hand: the previous version is at $previous, the new one at $target.\"",
                "}",
                "exit 1",
                ""));
        return String.join("\r\n", lines);
    }

    /** The script's first lines: its log, and the wait for the program to close. */
    private static List<String> waiting(String purpose, List<Long> waitFor, Path log, String... onGivingUp) {
        String pids = waitFor.stream().map(String::valueOf).collect(Collectors.joining(", "));
        List<String> lines = new ArrayList<>(List.of(
                "# Written by desktop-updater. " + purpose,
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
                "        Write-Log \"The program (process $id) is still running; the update was not installed.\""));
        for (String line : onGivingUp) {
            lines.add("        " + line);
        }
        lines.addAll(List.of(
                "        exit 1",
                "    }",
                "}"));
        return lines;
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
