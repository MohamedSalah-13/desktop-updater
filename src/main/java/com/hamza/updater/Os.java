package com.hamza.updater;

import java.util.Locale;

/**
 * The kind of system the program runs on, as far as a release's files go: a Windows installer is of no
 * use on a Mac.
 */
public enum Os {
    WINDOWS, LINUX, MAC, OTHER;

    /** The system this program runs on. */
    public static Os current() {
        return of(System.getProperty("os.name"));
    }

    /** The processor this program runs on, as release files are usually named: {@code x64} or {@code arm64}. */
    public static String arch() {
        return archOf(System.getProperty("os.arch"));
    }

    /** @param osName as the {@code os.name} system property has it */
    static Os of(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        // Before Windows: "Darwin" has "win" in it.
        if (name.contains("mac") || name.contains("darwin")) {
            return MAC;
        }
        if (name.startsWith("win")) {
            return WINDOWS;
        }
        if (name.contains("linux") || name.contains("nux")) {
            return LINUX;
        }
        return OTHER;
    }

    /**
     * @param osArch as the {@code os.arch} system property has it; a processor that is neither of the two
     *               common ones is named as the JVM names it
     */
    static String archOf(String osArch) {
        String arch = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }
}
