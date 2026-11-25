package com.hamza.updater;

import java.util.List;

/**
 * How the program's installer is told to run without asking questions. The arguments differ from one
 * installer maker to another, so the program says which one made its installer.
 *
 * <p>An {@code .msi} file is always run through {@code msiexec /i}, with these arguments after it.
 *
 * @param arguments      what follows the installer's path on its command line
 * @param replacesFolder the release is no installer but a zip of the program's folder: see {@link #ZIP}
 */
public record InstallerKind(List<String> arguments, boolean replacesFolder) {

    /**
     * An installer made by {@code jpackage} ({@code --type exe} or {@code msi}): Windows Installer's own
     * progress bar and no pages. The {@code .exe} hands its arguments on to {@code msiexec}.
     */
    public static final InstallerKind JPACKAGE = new InstallerKind(List.of("/passive", "/norestart"));

    /** An installer made by Inno Setup: its progress window and no pages. */
    public static final InstallerKind INNO_SETUP =
            new InstallerKind(List.of("/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART"));

    /** The installer's ordinary wizard, for the person to click through. Works with any installer. */
    public static final InstallerKind WIZARD = new InstallerKind(List.of());

    /**
     * No installer: a zip of the program's folder, as {@code jpackage --type app-image} makes it, for a
     * program that people unpack instead of install. The folder replaced is the one the program to open
     * again ({@link AppUpdater.Builder#relaunch}, by default the launcher that started this copy) lives in, and
     * the zip holds that launcher at its top or inside one folder at its top.
     *
     * <p>{@link AppUpdater#install} unpacks the zip beside the folder while the program is still running,
     * so a folder the program may not write next to, or a zip that is not this program's, is an error the
     * program can still show. Once the program has closed, the old folder is put aside, the new one takes its
     * place, and the program opens again. When it does not start - it ends with an error within
     * {@value InstallerLaunch#START_SECONDS} seconds - the old folder is put back and opened instead. Files
     * the person added to the folder do not move to the new one.
     */
    public static final InstallerKind ZIP = new InstallerKind(List.of(), true);

    public InstallerKind {
        arguments = List.copyOf(arguments);
        if (replacesFolder && !arguments.isEmpty()) {
            throw new IllegalArgumentException("A zip of the program's folder is not run, so it takes no arguments");
        }
    }

    /** An installer run with these arguments. */
    public InstallerKind(List<String> arguments) {
        this(arguments, false);
    }

    /** An installer with arguments of its own. */
    public static InstallerKind withArguments(String... arguments) {
        return new InstallerKind(List.of(arguments));
    }
}
