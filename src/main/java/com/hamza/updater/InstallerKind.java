package com.hamza.updater;

import java.util.List;

/**
 * How the program's installer is told to run without asking questions. The arguments differ from one
 * installer maker to another, so the program says which one made its installer.
 *
 * <p>An {@code .msi} file is always run through {@code msiexec /i}, with these arguments after it.
 *
 * @param arguments what follows the installer's path on its command line
 */
public record InstallerKind(List<String> arguments) {

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

    public InstallerKind {
        arguments = List.copyOf(arguments);
    }

    /** An installer with arguments of its own. */
    public static InstallerKind withArguments(String... arguments) {
        return new InstallerKind(List.of(arguments));
    }
}
