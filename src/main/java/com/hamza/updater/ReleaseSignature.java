package com.hamza.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The publisher's signature on a release: proof that the installer is the one the publisher built, whoever
 * controls the place it is downloaded from.
 *
 * <p>The SHA-256 a release publishes ties the file to the release, which is no help when the release itself
 * is forged - by somebody who took over the publisher's GitHub account, say. So the publisher signs each
 * release with a private key that is kept off GitHub, the program carries the matching public key, and a
 * release without a valid signature is never offered.
 *
 * <p><b>What is signed</b> is a short statement of the program's name, the version, and the installer's
 * SHA-256 and size. The name keeps one program's installer from being passed off as another's signed with
 * the same key; the version keeps an old, signed installer from being offered again as a new release.
 *
 * <p>Ed25519, which the JDK has had since 15 (in a runtime image built with JDK 21 or older it lives in
 * the {@code jdk.crypto.ec} module).
 *
 * <p>This file depends on nothing else in the library, so that it also runs on its own as the publisher's
 * tool: {@code java ReleaseSignature.java keygen|public|sign|verify ...} - see {@link #main}.
 */
public final class ReleaseSignature {

    private static final String ALGORITHM = "Ed25519";

    private ReleaseSignature() {
    }

    /**
     * The text that is signed. Every part is checked, so that no part can smuggle a line break in and say
     * something else.
     *
     * @throws IllegalArgumentException when a part is not what it should be
     */
    public static String statement(String app, String version, String sha256, long size) {
        String name = app == null ? "" : app.strip();
        String hash = sha256 == null ? "" : sha256.strip().toLowerCase(Locale.ROOT);
        String number = version == null ? "" : version.strip();
        if (name.isEmpty() || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Not a program's name: " + app);
        }
        if (!number.matches("[0-9A-Za-z.+-]{1,64}")) {
            throw new IllegalArgumentException("Not a version: " + version);
        }
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Not a SHA-256: " + sha256);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Not a file size: " + size);
        }
        return "desktop-updater release v1\napp=" + name + "\nversion=" + number + "\nsha256=" + hash
                + "\nsize=" + size + "\n";
    }

    /** Whether a text is a public key as {@code keygen} prints one. */
    public static boolean isPublicKey(String publicKey) {
        try {
            KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.strip())));
            return true;
        } catch (GeneralSecurityException | RuntimeException notAKey) {
            return false;
        }
    }

    /**
     * Whether {@code signature} is the publisher's signature of this release.
     *
     * @param publicKey the publisher's public key, Base64 as {@code keygen} printed it
     * @param signature Base64; anything else is simply not a valid signature
     */
    public static boolean verify(String publicKey, String signature, String app, String version, String sha256,
                                 long size) {
        try {
            PublicKey key = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.strip())));
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(statement(app, version, sha256, size).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature.strip()));
        } catch (GeneralSecurityException | RuntimeException notValid) {
            return false;
        }
    }

    /** @param privateKey Base64, as {@code keygen} wrote it */
    public static String sign(String privateKey, String app, String version, String sha256, long size)
            throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey.strip())));
        Signature signer = Signature.getInstance(ALGORITHM);
        signer.initSign(key);
        signer.update(statement(app, version, sha256, size).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** A new key pair: the private key first, the public key second, both Base64. */
    public static String[] generate() throws GeneralSecurityException {
        KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        return new String[] {
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())};
    }

    // ------------------------------------------------------------------------------- the publisher's tool

    private static final String USAGE = """
            The publisher's side of desktop-updater's release signatures.

              keygen --key <file>
                  Makes a new key pair. The private key is written to <file> (never over an existing one);
                  the public key is printed, to be given to the program: .signedBy("...").
              public --key <file>
                  Prints the public key of a key file.
              sign   --key <file> --app <name> --version <x.y.z> --file <installer> [--out <file>]
                  Signs an installer. The signature goes to <installer>.sig unless --out says otherwise.
              verify --public <key> --app <name> --version <x.y.z> --file <installer> [--sig <file>]
                  Says whether a signature is the publisher's. Exit code 0 when it is.

            Keep the key file out of every repository, and keep a copy somewhere safe: programs that carry
            its public key accept no release signed with another.
            """;

    /** Exit code 0 on success, 1 for a signature that is not valid, 2 for a mistake in the call. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            if (args.length == 0) {
                System.out.print(USAGE);
                return 2;
            }
            Map<String, String> options = options(args);
            switch (args[0]) {
                case "keygen" -> {
                    Path file = Path.of(need(options, "key"));
                    String[] pair = generate();
                    Properties keys = new Properties();
                    keys.setProperty("private", pair[0]);
                    keys.setProperty("public", pair[1]);
                    if (file.toAbsolutePath().getParent() != null) {
                        Files.createDirectories(file.toAbsolutePath().getParent());
                    }
                    try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        keys.store(out, "desktop-updater release signing key. Secret: keep it out of every repository.");
                    }
                    System.out.println(pair[1]);
                    return 0;
                }
                case "public" -> {
                    System.out.println(key(Path.of(need(options, "key")), "public"));
                    return 0;
                }
                case "sign" -> {
                    Path file = Path.of(need(options, "file"));
                    String signature = sign(key(Path.of(need(options, "key")), "private"), need(options, "app"),
                            need(options, "version"), sha256(file), Files.size(file));
                    Path out = options.containsKey("out") ? Path.of(options.get("out"))
                            : file.resolveSibling(file.getFileName() + ".sig");
                    Files.writeString(out, signature + "\n", StandardCharsets.US_ASCII);
                    System.out.println(out.toAbsolutePath());
                    return 0;
                }
                case "verify" -> {
                    Path file = Path.of(need(options, "file"));
                    Path sig = options.containsKey("sig") ? Path.of(options.get("sig"))
                            : file.resolveSibling(file.getFileName() + ".sig");
                    boolean valid = verify(need(options, "public"), Files.readString(sig, StandardCharsets.US_ASCII),
                            need(options, "app"), need(options, "version"), sha256(file), Files.size(file));
                    System.out.println(valid ? "valid" : "NOT VALID");
                    return valid ? 0 : 1;
                }
                default -> {
                    System.err.println("Unknown command: " + args[0]);
                    System.err.print(USAGE);
                    return 2;
                }
            }
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            System.err.println(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return 2;
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value pairs, got: " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static String need(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is missing");
        }
        return value;
    }

    private static String key(Path file, String which) throws IOException {
        Properties keys = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            keys.load(in);
        }
        String key = keys.getProperty(which);
        if (key == null || key.isBlank()) {
            throw new IOException(file + " is not a key file made by keygen");
        }
        return key;
    }

    private static String sha256(Path file) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
