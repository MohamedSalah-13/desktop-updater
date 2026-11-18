package com.hamza.updater;

import java.net.URI;
import java.util.Optional;

/**
 * A published release and the installer it offers.
 *
 * @param title what the release is called; never null, may be empty
 * @param notes what changed, as the publisher wrote it (Markdown on GitHub); never null, may be empty
 * @param page  where a person can read about the release and download it by hand
 */
public record Release(Version version, String title, String notes, Optional<URI> page, Installer installer,
                      boolean preRelease) {

    public Release {
        title = title == null ? "" : title;
        notes = notes == null ? "" : notes;
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
