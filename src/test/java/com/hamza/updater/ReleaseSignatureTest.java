package com.hamza.updater;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSignatureTest {

    private static final String SHA = "9e08356040c651fbcf3134a702854cadfc4eaae15390dd159cb4e4f32dcb2d0e";
    private static final URI SIG = URI.create("https://github.com/o/a/releases/download/v2.5.0/App-setup.exe.sig");

    private static String privateKey;
    private static String publicKey;
    private static String otherPublicKey;

    @TempDir
    Path folder;

    @BeforeAll
    static void keys() throws Exception {
        String[] pair = ReleaseSignature.generate();
        privateKey = pair[0];
        publicKey = pair[1];
        otherPublicKey = ReleaseSignature.generate()[1];
    }

    @Test
    void aSignatureHoldsForExactlyWhatWasSigned() throws Exception {
        String signature = ReleaseSignature.sign(privateKey, "File Transfer", "2.5.0", SHA, 1000);
        assertTrue(ReleaseSignature.verify(publicKey, signature, "File Transfer", "2.5.0", SHA, 1000));
        assertTrue(ReleaseSignature.verify(publicKey, " " + signature + "\n", "File Transfer", "2.5.0", SHA.toUpperCase(), 1000));

        assertFalse(ReleaseSignature.verify(publicKey, signature, "Git Backup", "2.5.0", SHA, 1000), "another program");
        assertFalse(ReleaseSignature.verify(publicKey, signature, "File Transfer", "2.6.0", SHA, 1000), "another version");
        assertFalse(ReleaseSignature.verify(publicKey, signature, "File Transfer", "2.5.0", SHA.replace('9', '8'), 1000), "another file");
        assertFalse(ReleaseSignature.verify(publicKey, signature, "File Transfer", "2.5.0", SHA, 1001), "another size");
        assertFalse(ReleaseSignature.verify(otherPublicKey, signature, "File Transfer", "2.5.0", SHA, 1000), "another key");
    }

    @Test
    void whatIsNotASignatureIsSimplyNotValid() {
        assertFalse(ReleaseSignature.verify(publicKey, "", "App", "1.0.0", SHA, 10));
        assertFalse(ReleaseSignature.verify(publicKey, "not base64 !!", "App", "1.0.0", SHA, 10));
        assertFalse(ReleaseSignature.verify(publicKey, "AAAA", "App", "1.0.0", SHA, 10));
        assertFalse(ReleaseSignature.verify("not a key", "AAAA", "App", "1.0.0", SHA, 10));
        assertFalse(ReleaseSignature.verify(publicKey, "AAAA", "App", "1.0.0", "short", 10));
        assertTrue(ReleaseSignature.isPublicKey(publicKey));
        assertFalse(ReleaseSignature.isPublicKey(privateKey));
        assertFalse(ReleaseSignature.isPublicKey("hello"));
    }

    @Test
    void noPartOfTheStatementCanCarryALineBreak() {
        assertEquals("desktop-updater release v1\napp=App\nversion=1.0.0\nsha256=" + SHA + "\nsize=10\n",
                ReleaseSignature.statement(" App ", "1.0.0", SHA.toUpperCase(), 10));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignature.statement("App\nversion=9.9.9", "1.0.0", SHA, 10));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignature.statement("App", "1.0.0\nsize=1", SHA, 10));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignature.statement("", "1.0.0", SHA, 10));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignature.statement("App", "1.0.0", SHA, 0));
    }

    @Test
    void theToolMakesAKeySignsAndVerifies() throws Exception {
        Path key = folder.resolve("keys").resolve("release.key");
        Path installer = Files.write(folder.resolve("App 2.5.0 setup.exe"), new byte[5000]);
        assertEquals(0, ReleaseSignature.run(new String[] {"keygen", "--key", key.toString()}));
        assertEquals(2, ReleaseSignature.run(new String[] {"keygen", "--key", key.toString()}), "never over an existing key");
        java.util.Properties made = new java.util.Properties();
        try (var in = Files.newInputStream(key)) {
            made.load(in);
        }
        String ours = made.getProperty("public");
        assertTrue(ReleaseSignature.isPublicKey(ours));

        assertEquals(0, ReleaseSignature.run(new String[] {"sign", "--key", key.toString(), "--app", "My App",
                "--version", "2.5.0", "--file", installer.toString()}));
        Path sig = folder.resolve("App 2.5.0 setup.exe.sig");
        assertTrue(Files.isRegularFile(sig));
        assertEquals(0, ReleaseSignature.run(new String[] {"verify", "--public", ours, "--app", "My App",
                "--version", "2.5.0", "--file", installer.toString()}));
        assertEquals(1, ReleaseSignature.run(new String[] {"verify", "--public", ours, "--app", "My App",
                "--version", "2.5.1", "--file", installer.toString()}));
        Files.write(installer, new byte[5001]);
        assertEquals(1, ReleaseSignature.run(new String[] {"verify", "--public", ours, "--app", "My App",
                "--version", "2.5.0", "--file", installer.toString()}), "the file changed after it was signed");
        assertEquals(2, ReleaseSignature.run(new String[] {"sign", "--app", "My App"}), "a call with parts missing");
    }

    // ------------------------------------------------------------------ a program that carries the key

    private static Release release(Optional<String> signature, Optional<URI> signatureUrl, Optional<String> sha256) {
        return new Release(Version.of("2.5.0"), "App 2.5.0", "notes", Optional.empty(),
                new Release.Installer("App-setup.exe", URI.create("https://github.com/o/a/releases/download/v2.5.0/App-setup.exe"),
                        1000, sha256, signature, signatureUrl), false);
    }

    private AppUpdater.Builder program(Release release) {
        return AppUpdater.builder("App").currentVersion("2.4.0").source(() -> Optional.of(release)).folder(folder);
    }

    @Test
    void aSignedReleaseIsOfferedAndItsSignatureIsFetchedOnlyThen() throws Exception {
        String signature = ReleaseSignature.sign(privateKey, "App", "2.5.0", SHA, 1000);
        List<URI> fetched = new ArrayList<>();
        AppUpdater updater = program(release(Optional.empty(), Optional.of(SIG), Optional.of(SHA))).signedBy(publicKey)
                .signatures(address -> {
                    fetched.add(address);
                    return signature;
                }).build();
        assertInstanceOf(CheckResult.Available.class, updater.check());
        assertEquals(List.of(SIG), fetched);

        AppUpdater inline = program(release(Optional.of(signature), Optional.empty(), Optional.of(SHA)))
                .signedBy(publicKey).build();
        assertInstanceOf(CheckResult.Available.class, inline.check());
    }

    @Test
    void anUnsignedOrForgedReleaseIsNeverOffered() throws Exception {
        String forged = ReleaseSignature.sign(ReleaseSignature.generate()[0], "App", "2.5.0", SHA, 1000);
        String ofAnotherVersion = ReleaseSignature.sign(privateKey, "App", "2.4.9", SHA, 1000);
        String good = ReleaseSignature.sign(privateKey, "App", "2.5.0", SHA, 1000);

        CheckResult unsigned = program(release(Optional.empty(), Optional.empty(), Optional.of(SHA))).signedBy(publicKey).build().check();
        assertTrue(assertInstanceOf(CheckResult.Failed.class, unsigned).reason().contains("not signed"));

        for (String bad : List.of(forged, ofAnotherVersion, "garbage", "A".repeat(600))) {
            CheckResult refused = program(release(Optional.of(bad), Optional.empty(), Optional.of(SHA))).signedBy(publicKey).build().check();
            assertTrue(assertInstanceOf(CheckResult.Failed.class, refused).reason().contains("not the publisher's"), bad);
        }

        CheckResult noHash = program(release(Optional.of(good), Optional.empty(), Optional.empty())).signedBy(publicKey).build().check();
        assertInstanceOf(CheckResult.Failed.class, noHash);

        CheckResult unreachable = program(release(Optional.empty(), Optional.of(SIG), Optional.of(SHA))).signedBy(publicKey)
                .signatures(address -> {
                    throw new java.io.IOException("HTTP 404");
                }).build().check();
        assertInstanceOf(CheckResult.Failed.class, unreachable);
    }

    @Test
    void aProgramWithoutAKeyTakesReleasesAsBefore() {
        assertInstanceOf(CheckResult.Available.class,
                program(release(Optional.empty(), Optional.empty(), Optional.of(SHA))).build().check());
        assertThrows(IllegalArgumentException.class, () -> AppUpdater.builder("App").signedBy("not a key"));
    }

    @Test
    void anUpToDateProgramNeedsNoSignature() {
        AppUpdater updater = AppUpdater.builder("App").currentVersion("2.5.0").folder(folder).signedBy(publicKey)
                .source(() -> Optional.of(release(Optional.empty(), Optional.empty(), Optional.of(SHA)))).build();
        assertInstanceOf(CheckResult.UpToDate.class, updater.check());
    }
}
