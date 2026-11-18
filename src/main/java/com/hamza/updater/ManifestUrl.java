package com.hamza.updater;

import com.hamza.updater.internal.Http;
import com.hamza.updater.internal.Json;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A release described by a JSON file on the publisher's own server - for a program whose repository is
 * private, or that is not on GitHub at all:
 *
 * <pre>{@code
 * {
 *   "version": "2.5.0",
 *   "url": "https://example.com/downloads/Program-2.5.0-setup.exe",
 *   "sha256": "9e08...2d0e",
 *   "size": 34853376,
 *   "title": "Program 2.5.0",
 *   "notes": "What changed.",
 *   "page": "https://example.com/releases/2.5.0",
 *   "minimum": "2.4.0"
 * }
 * }</pre>
 *
 * <p>{@code version}, {@code url} and {@code sha256} are required: the file may sit on any server, so its
 * fingerprint is what ties it to this manifest. {@code minimum} is the oldest version that may go on
 * running; a copy older than it is required to install the release. A 204 or 404 answer means nothing is
 * published.
 */
public final class ManifestUrl implements ReleaseSource {

    private final URI address;
    private final GitHubReleases.Fetch fetch;

    ManifestUrl(URI address) {
        this(address, Http::get);
    }

    private ManifestUrl(URI address, GitHubReleases.Fetch fetch) {
        this.address = address;
        this.fetch = fetch;
    }

    /** With another way to ask, for a test. */
    ManifestUrl fetchingWith(GitHubReleases.Fetch other) {
        return new ManifestUrl(address, other);
    }

    @Override
    public Optional<Release> latest() throws IOException {
        Http.Text answer = fetch.get(address, Map.of("Accept", "application/json"));
        if (answer.status() == 204 || answer.status() == 404) {
            return Optional.empty();
        }
        if (answer.status() != 200) {
            throw new IOException("The update server answered HTTP " + answer.status());
        }
        Map<String, Object> manifest;
        try {
            manifest = Json.object(Json.parse(answer.body()));
        } catch (IllegalArgumentException notJson) {
            throw new IOException("The update manifest is not JSON: " + notJson.getMessage());
        }
        Version version = Version.parse(Json.text(manifest, "version"))
                .orElseThrow(() -> new IOException("The update manifest has no version"));
        String sha256 = Json.text(manifest, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("The update manifest has no SHA-256 of its file");
        }
        URI url = address(Json.text(manifest, "url"))
                .orElseThrow(() -> new IOException("The update manifest has no address of its file"));
        Http.requireSafe(url);
        String path = url.getPath() == null ? "" : url.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1);
        Release.Installer installer =
                new Release.Installer(name, url, Json.number(manifest, "size", -1), Optional.of(sha256));
        // A minimum above the release itself could never be met; it is read as none.
        Optional<Version> minimum = Version.parse(Json.text(manifest, "minimum"))
                .filter(oldest -> oldest.compareTo(version) <= 0);
        return Optional.of(new Release(version, Json.text(manifest, "title"), Json.text(manifest, "notes"),
                address(Json.text(manifest, "page")), installer, version.isPreRelease(), minimum));
    }

    private static Optional<URI> address(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(URI.create(text.strip()));
        } catch (IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
    }
}
