package com.hamza.updater;

import com.hamza.updater.internal.Http;
import com.hamza.updater.internal.Json;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The releases of a public GitHub repository, read without a token ({@code GET /repos/{owner}/{name}/releases}).
 *
 * <p>A release counts when it is published (not a draft), its tag is a version ({@code v2.4.0}), and it has
 * an asset whose name matches {@link #asset} (or {@link #assetFor} for the system the program runs on). The
 * newest by version wins, not the newest by date. GitHub
 * gives every asset's SHA-256, so the download is checked against it.
 *
 * <p>A release whose description holds {@code <!-- update: required -->} is one no older copy may skip
 * ({@link Release#minimum}).
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
    private final Map<Os, String> assetGlobs;
    private final boolean preReleases;
    private final Fetch fetch;
    private final Os os;
    private final String arch;

    GitHubReleases(String repository) {
        this(repository, "*.exe", Map.of(), false, Http::get, Os.current(), Os.arch());
    }

    private GitHubReleases(String repository, String assetGlob, Map<Os, String> assetGlobs, boolean preReleases,
                           Fetch fetch, Os os, String arch) {
        if (repository == null || !REPOSITORY.matcher(repository).matches()) {
            throw new IllegalArgumentException("Not an owner/name repository: " + repository);
        }
        this.repository = repository;
        this.assetGlob = assetGlob;
        this.assetGlobs = Map.copyOf(assetGlobs);
        this.preReleases = preReleases;
        this.fetch = fetch;
        this.os = os;
        this.arch = arch;
    }

    /**
     * Which of a release's files is the installer, by name: {@code *} stands for any text and {@code ?} for
     * one character, upper and lower case alike. {@code *.exe} unless said otherwise. It holds on every
     * system that {@link #assetFor} does not name.
     */
    public GitHubReleases asset(String glob) {
        return new GitHubReleases(repository, glob, assetGlobs, preReleases, fetch, os, arch);
    }

    /**
     * The installer for one kind of system, for a program that is published for several:
     *
     * <pre>{@code
     * ReleaseSource.github("owner/app")
     *         .asset("*-setup.exe")
     *         .assetFor(Os.LINUX, "*-linux-{arch}.deb")
     *         .assetFor(Os.MAC, "*-macos-{arch}.dmg")
     * }</pre>
     *
     * <p>{@code {arch}} stands for the processor the program runs on, {@code x64} or {@code arm64}
     * ({@link Os#arch}). A release that has no file for the system the program runs on is passed over
     * there - nobody is offered an update they cannot install.
     */
    public GitHubReleases assetFor(Os system, String glob) {
        Map<Os, String> globs = new java.util.EnumMap<>(Os.class);
        globs.putAll(assetGlobs);
        globs.put(system, glob);
        return new GitHubReleases(repository, assetGlob, globs, preReleases, fetch, os, arch);
    }

    /** Whether releases marked "pre-release" on GitHub are offered too. They are not unless said. */
    public GitHubReleases includePreReleases(boolean include) {
        return new GitHubReleases(repository, assetGlob, assetGlobs, include, fetch, os, arch);
    }

    /** With another way to ask, for a test. */
    GitHubReleases fetchingWith(Fetch other) {
        return new GitHubReleases(repository, assetGlob, assetGlobs, preReleases, other, os, arch);
    }

    /** As if run on another system, for a test. */
    GitHubReleases onSystem(Os system, String processor) {
        return new GitHubReleases(repository, assetGlob, assetGlobs, preReleases, fetch, system, processor);
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
        Pattern installerName = glob(assetGlobs.getOrDefault(os, assetGlob).replace("{arch}", arch));
        Release newest = null;
        List<Version> required = new ArrayList<>();
        for (Object entry : Json.array(parsed)) {
            Map<String, Object> release = Json.object(entry);
            Optional<Version> version = Version.parse(Json.text(release, "tag_name"));
            boolean preRelease = Json.flag(release, "prerelease") || version.map(Version::isPreRelease).orElse(false);
            if (version.isEmpty() || Json.flag(release, "draft") || (preRelease && !preReleases)) {
                continue;
            }
            String body = Json.text(release, "body");
            if (Release.REQUIRED_MARK.matcher(body).find()) {
                required.add(version.get());
            }
            Optional<Release.Installer> installer = installer(release, installerName);
            if (installer.isEmpty() || (newest != null && !version.get().isNewerThan(newest.version()))) {
                continue;
            }
            newest = new Release(version.get(), Json.text(release, "name"), body,
                    page(Json.text(release, "html_url")), installer.get(), preRelease);
        }
        if (newest == null) {
            return Optional.empty();
        }
        // A required release stays required once a later one replaces it as the newest: whoever is older
        // than it still has to update. One that is newer than what is offered cannot be asked for yet.
        Version offered = newest.version();
        Optional<Version> minimum = required.stream()
                .filter(version -> version.compareTo(offered) <= 0)
                .max(Comparator.naturalOrder());
        return Optional.of(new Release(newest.version(), newest.title(), newest.notes(), newest.page(),
                newest.installer(), newest.preRelease(), minimum));
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
            return Optional.of(new Release.Installer(name, url, Json.number(asset, "size", -1), sha256,
                    Optional.empty(), assetUrl(release, name + ".sig")));
        }
        return Optional.empty();
    }

    /** The address of the release's file of exactly this name, when it has one; a GitHub address or none. */
    private static Optional<URI> assetUrl(Map<String, Object> release, String wanted) {
        for (Object entry : Json.array(release.get("assets"))) {
            Map<String, Object> asset = Json.object(entry);
            String state = Json.text(asset, "state");
            if (!Json.text(asset, "name").equalsIgnoreCase(wanted) || !(state.isEmpty() || state.equals("uploaded"))) {
                continue;
            }
            try {
                URI url = URI.create(Json.text(asset, "browser_download_url"));
                if ("https".equalsIgnoreCase(url.getScheme()) && "github.com".equalsIgnoreCase(url.getHost())) {
                    return Optional.of(url);
                }
            } catch (IllegalArgumentException notAnAddress) {
                // Not a file that can be fetched.
            }
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
