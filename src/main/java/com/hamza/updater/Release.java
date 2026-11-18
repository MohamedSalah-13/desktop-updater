package com.hamza.updater;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A published release and the installer it offers.
 *
 * @param title   what the release is called; never null, may be empty
 * @param notes   what changed, as the publisher wrote it (Markdown on GitHub) with HTML comments taken out;
 *                never null, may be empty
 * @param page    where a person can read about the release and download it by hand
 * @param minimum the oldest version that may go on running: a copy older than it is <em>required</em> to
 *                install this release. Empty when no release on the way here was marked required.
 */
public record Release(Version version, String title, String notes, Optional<URI> page, Installer installer,
                      boolean preRelease, Optional<Version> minimum) {

    /**
     * What a publisher writes in a release's description to make it required: {@code <!-- update: required -->}.
     * An HTML comment, so GitHub's page does not show it.
     */
    public static final Pattern REQUIRED_MARK =
            Pattern.compile("<!--\\s*update\\s*:\\s*required\\s*-->", Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    public Release {
        title = title == null ? "" : title;
        notes = notes == null ? "" : HTML_COMMENT.matcher(notes).replaceAll("").strip();
        minimum = minimum == null ? Optional.empty() : minimum;
    }

    /** A release nobody is required to install. */
    public Release(Version version, String title, String notes, Optional<URI> page, Installer installer,
                   boolean preRelease) {
        this(version, title, notes, page, installer, preRelease, Optional.empty());
    }

    /** Whether a copy of this version must install the release: it is older than {@link #minimum}. */
    public boolean isRequiredFor(Version running) {
        return minimum.isPresent() && running.compareTo(minimum.get()) < 0;
    }

    /**
     * The installer file.
     *
     * @param size   bytes; zero or less when the publisher did not say
     * @param sha256 hex of the file's SHA-256; empty when the publisher did not say, and then only the size
     *               is checked after the download
     */
    public record Installer(String name, URI url, long size, Optional<String> sha256) {
    }
}
