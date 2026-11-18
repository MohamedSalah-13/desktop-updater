package com.hamza.updater;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** Where a program learns of its newest release. */
@FunctionalInterface
public interface ReleaseSource {

    /**
     * The newest release that offers an installer; empty when nothing is published.
     *
     * @throws IOException when there is no answer, or the answer is not what the source should give
     */
    Optional<Release> latest() throws IOException;

    /**
     * The releases of a public GitHub repository.
     *
     * @param repository {@code owner/name}
     */
    static GitHubReleases github(String repository) {
        return new GitHubReleases(repository);
    }

    /**
     * A JSON file on a server of the publisher's own, for a program whose repository is private:
     * {@code {"version": "2.5.0", "url": "https://.../Setup.exe", "sha256": "...", "size": 123, "notes": "..."}}.
     */
    static ManifestUrl manifest(String url) {
        return new ManifestUrl(URI.create(url));
    }
}
