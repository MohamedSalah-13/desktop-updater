package com.hamza.updater;

import com.hamza.updater.internal.Http;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleasesTest {

    private static final String SHA = "9e08356040c651fbcf3134a702854cadfc4eaae15390dd159cb4e4f32dcb2d0e";

    private static String release(String tag, boolean draft, boolean preRelease, String... assets) {
        List<String> files = new ArrayList<>();
        for (String name : assets) {
            files.add("""
                    {"name": "%s", "state": "uploaded", "size": 1000, "digest": "sha256:%s",
                     "browser_download_url": "https://github.com/owner/app/releases/download/%s/%s"}"""
                    .formatted(name, SHA, tag, name));
        }
        return """
                {"tag_name": "%s", "name": "App %s", "body": "Notes of %s", "draft": %s, "prerelease": %s,
                 "html_url": "https://github.com/owner/app/releases/tag/%s", "assets": [%s]}"""
                .formatted(tag, tag, tag, draft, preRelease, tag, String.join(",", files));
    }

    private static GitHubReleases answering(String... releases) {
        return ReleaseSource.github("owner/app")
                .fetchingWith((uri, headers) -> new Http.Text(200, "[" + String.join(",", releases) + "]"));
    }

    @Test
    void asksTheRepositorysReleases() throws IOException {
        List<URI> asked = new ArrayList<>();
        ReleaseSource.github("MohamedSalah-13/file-transfer").fetchingWith((uri, headers) -> {
            asked.add(uri);
            return new Http.Text(200, "[]");
        }).latest();
        assertEquals(List.of(URI.create("https://api.github.com/repos/MohamedSalah-13/file-transfer/releases?per_page=30")),
                asked);
    }

    @Test
    void theNewestByVersionWinsWhateverTheOrder() throws IOException {
        Release latest = answering(
                release("v2.9.0", false, false, "App-2.9.0-setup.exe"),
                release("v2.10.0", false, false, "App-2.10.0-setup.exe"),
                release("v2.3.0", false, false, "App-2.3.0-setup.exe")).latest().orElseThrow();
        assertEquals(Version.of("2.10.0"), latest.version());
        assertEquals("App v2.10.0", latest.title());
        assertEquals("Notes of v2.10.0", latest.notes());
        assertEquals(Optional.of(URI.create("https://github.com/owner/app/releases/tag/v2.10.0")), latest.page());
        assertEquals("App-2.10.0-setup.exe", latest.installer().name());
        assertEquals(1000, latest.installer().size());
        assertEquals(Optional.of(SHA), latest.installer().sha256());
        assertFalse(latest.preRelease());
    }

    @Test
    void draftsAndPreReleasesAreNotOffered() throws IOException {
        GitHubReleases source = answering(
                release("v3.0.0", true, false, "App-3.0.0-setup.exe"),
                release("v2.6.0", false, true, "App-2.6.0-setup.exe"),
                release("v2.5.0-beta.1", false, false, "App-2.5.0-beta.1-setup.exe"),
                release("v2.4.0", false, false, "App-2.4.0-setup.exe"));
        assertEquals(Version.of("2.4.0"), source.latest().orElseThrow().version());
        Release withPreReleases = source.includePreReleases(true).latest().orElseThrow();
        assertEquals(Version.of("2.6.0"), withPreReleases.version());
        assertTrue(withPreReleases.preRelease());
    }

    @Test
    void aReleaseWithoutTheInstallerIsPassedOver() throws IOException {
        GitHubReleases source = answering(
                release("v2.5.0", false, false, "App-2.5.0.jar", "notes.txt"),
                release("v2.4.0", false, false, "App-2.4.0.msi", "App-2.4.0-SETUP.EXE"),
                release("not-a-version", false, false, "App-setup.exe"));
        assertEquals(Version.of("2.4.0"), source.latest().orElseThrow().version());
        assertEquals("App-2.4.0-SETUP.EXE", source.asset("*-setup.exe").latest().orElseThrow().installer().name());
        assertEquals("App-2.4.0.msi", source.asset("*.msi").latest().orElseThrow().installer().name());
        assertTrue(source.asset("*.dmg").latest().isEmpty());
    }

    @Test
    void aFileServedFromElsewhereIsNotAnInstaller() throws IOException {
        String elsewhere = release("v2.5.0", false, false, "App-2.5.0-setup.exe")
                .replace("https://github.com/owner/app/releases/download", "https://example.com/files");
        assertTrue(answering(elsewhere).latest().isEmpty());
    }

    @Test
    void nothingPublishedIsEmpty() throws IOException {
        assertTrue(answering().latest().isEmpty());
    }

    @Test
    void anAnswerThatIsNotReleasesIsAFailure() {
        GitHubReleases limited = ReleaseSource.github("owner/app").fetchingWith((uri, headers) -> new Http.Text(403, ""));
        assertThrows(IOException.class, limited::latest);
        GitHubReleases page = ReleaseSource.github("owner/app")
                .fetchingWith((uri, headers) -> new Http.Text(200, "<html>Bad gateway</html>"));
        assertThrows(IOException.class, page::latest);
    }

    @Test
    void theRepositoryIsOwnerSlashName() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseSource.github("file-transfer"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSource.github("owner/app/../other"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSource.github("https://github.com/owner/app"));
    }

    @Test
    void aManifestOnTheProgramsOwnServer() throws IOException {
        ManifestUrl source = ReleaseSource.manifest("https://example.com/app/latest.json").fetchingWith(
                (uri, headers) -> new Http.Text(200, """
                        {"version": "2.5.0", "url": "https://example.com/app/App-2.5.0-setup.exe",
                         "sha256": "%s", "size": 2000, "notes": "ما الجديد"}""".formatted(SHA.toUpperCase())));
        Release release = source.latest().orElseThrow();
        assertEquals(Version.of("2.5.0"), release.version());
        assertEquals("App-2.5.0-setup.exe", release.installer().name());
        assertEquals(Optional.of(SHA), release.installer().sha256());
        assertEquals("ما الجديد", release.notes());
    }

    @Test
    void aManifestNeedsAFingerprintAndAnHttpsFile() {
        ManifestUrl noHash = ReleaseSource.manifest("https://example.com/latest.json").fetchingWith(
                (uri, headers) -> new Http.Text(200, "{\"version\": \"2.5.0\", \"url\": \"https://example.com/a.exe\"}"));
        assertThrows(IOException.class, noHash::latest);
        ManifestUrl plainHttp = ReleaseSource.manifest("https://example.com/latest.json").fetchingWith(
                (uri, headers) -> new Http.Text(200,
                        "{\"version\": \"2.5.0\", \"url\": \"http://example.com/a.exe\", \"sha256\": \"" + SHA + "\"}"));
        assertThrows(IOException.class, plainHttp::latest);
        ManifestUrl nothing = ReleaseSource.manifest("https://example.com/latest.json")
                .fetchingWith((uri, headers) -> new Http.Text(404, ""));
        assertDoesNotThrowAndEmpty(nothing);
    }

    private static void assertDoesNotThrowAndEmpty(ManifestUrl source) {
        try {
            assertTrue(source.latest().isEmpty());
        } catch (IOException unexpected) {
            throw new AssertionError(unexpected);
        }
    }
}
