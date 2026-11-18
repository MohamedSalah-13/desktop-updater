package com.hamza.updater;

import com.hamza.updater.internal.Http;
import com.hamza.updater.internal.Json;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The releases of a public GitHub repository, read without a token ({@code GET /repos/{owner}/{name}/releases}).
 *
 * <p>A release counts when it is published (not a draft), its tag is a version ({@code v2.4.0}), and it has
 * an asset whose name matches {@link #asset}. The newest by version wins, not the newest by date. GitHub
 * gives every asset's SHA-256, so the download is checked against it.
 *
 * <p>GitHub answers sixty of these an hour to one address, which a program asking a few times a day stays
 * far below.
 */
public final class GitHubReleases implements ReleaseSource {

    /** One GET and its answer. */
    @FunctionalInterface
    interface Fetch {
        Http.Text get(URI uri, Map<String, String> headers) throws IOException;
    }

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Map<String, String> HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "X-GitHub-Api-Version", "2022-11-28");

    private final String repository;
    private final String assetGlob;
    private final boolean preReleases;
    private final Fetch fetch;

    GitHubReleases(String repository) {
        this(repository, "*.exe", false, Http::get);
    }

    private GitHubReleases(String repository, String assetGlob, boolean preReleases, Fetch fetch) {
        if (repository == null || !REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException("Not an owner/name repository: " + repository);
        }
        this.repository = repository;
        this.assetGlob = assetGlob;
        this.preReleases = preReleases;
        this.fetch = fetch;
    }

    /**
     * Which of a release's files is the installer, by name: {@code *} stands for any text and {@code ?} for
     * one character, upper and lower case alike. {@code *.exe} unless said otherwise.
     */
    public GitHubReleases asset(String glob) {
        return new GitHubReleases(repository, glob, preReleases, fetch);
    }

    /** Whether releases marked "pre-release" on GitHub are offered too. They are not unless said. */
    public GitHubReleases includePreReleases(boolean include) {
        return new GitHubReleases(repository, assetGlob, include, fetch);
    }

    /** With another way to ask, for a test. */
    GitHubReleases fetchingWith(Fetch other) {
        return new GitHubReleases(repository, assetGlob, preReleases, other);
    }

    @Override
    public Optional<Release> latest() throws IOException {
        URI uri = URI.create("https://api.github.com/repos/" + repository + "/releases?per_page=30");
        Http.Text answer = fetch.get(uri, HEADERS);
        if (answer.status() != 200) {
            throw new IOException("GitHub answered HTTP " + answer.status() + " for " + repository);
        }
        Object parsed;
        try {
            parsed = Json.parse(answer.body());
        } catch (IllegalArgumentException notJson) {
            throw new IOException("GitHub's answer is not a list of releases: " + notJson.getMessage());
        }
        Pattern installerName = glob(assetGlob);
        Release newest = null;
        for (Object entry : Json.array(parsed)) {
            Map<String, Object> release = Json.object(entry);
            Optional<Version> version = Version.parse(Json.text(release, "tag_name"));
            boolean preRelease = Json.flag(release, "prerelease") || version.map(Version::isPreRelease).orElse(false);
            if (version.isEmpty() || Json.flag(release, "draft") || (preRelease && !preReleases)) {
                continue;
            }
            Optional<Release.Installer> installer = installer(release, installerName);
            if (installer.isEmpty() || (newest != null && !version.get().isNewerThan(newest.version()))) {
                continue;
            }
            newest = new Release(version.get(), Json.text(release, "name"), Json.text(release, "body"),
                    page(Json.text(release, "html_url")), installer.get(), preRelease);
        }
        return Optional.ofNullable(newest);
    }

    private static Optional<Release.Installer> installer(Map<String, Object> release, Pattern installerName) {
        for (Object entry : Json.array(release.get("assets"))) {
            Map<String, Object> asset = Json.object(entry);
            String name = Json.text(asset, "name");
            String state = Json.text(asset, "state");
            if (!installerName.matcher(name).matches() || !(state.isEmpty() || state.equals("uploaded"))) {
                continue;
            }
            URI url;
            try {
                url = URI.create(Json.text(asset, "browser_download_url"));
            } catch (IllegalArgumentException notAnAddress) {
                continue;
            }
            // A release's files are served from github.com; anything else is not a GitHub asset.
            if (!"https".equalsIgnoreCase(url.getScheme()) || !"github.com".equalsIgnoreCase(url.getHost())) {
                continue;
            }
            String digest = Json.text(asset, "digest").toLowerCase(Locale.ROOT);
            Optional<String> sha256 = digest.matches("sha256:[0-9a-f]{64}")
                    ? Optional.of(digest.substring("sha256:".length()))
                    : Optional.empty();
            return Optional.of(new Release.Installer(name, url, Json.number(asset, "size", -1), sha256));
        }
        return Optional.empty();
    }

    private static Optional<URI> page(String address) {
        try {
            URI uri = URI.create(address);
            return "https".equalsIgnoreCase(uri.getScheme()) ? Optional.of(uri) : Optional.empty();
        } catch (IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
    }

    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
