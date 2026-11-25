package com.hamza.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A release that is a zip of the program's folder ({@link InstallerKind#ZIP}), unpacked beside the folder it
 * is to replace.
 *
 * <p>Beside it, on the same disk, so that putting the new folder in place is a rename: the program is away
 * only for as long as two renames take, and a rename either happens whole or not at all.
 *
 * <p>Windows PowerShell's {@code Compress-Archive} writes {@code \} between the parts of a name where the
 * zip format says {@code /}; both are read as a separator.
 */
final class ZipFolder {

    private ZipFolder() {
    }

    /**
     * @param target   the program's folder
     * @param staged   the zip's folder, unpacked beside it: {@code <folder>.update}
     * @param previous where the program's folder is put aside: {@code <folder>.previous}
     */
    record Swap(Path target, Path staged, Path previous) {
    }

    /**
     * Unpacks the zip's copy of the program's folder beside the folder of {@code program}.
     *
     * @param program the program's launcher, in the folder to replace
     * @throws IOException when the zip does not hold the launcher, names a file outside its folder, or the
     *                     folder beside the program's cannot be written; nothing is left behind then
     */
    static Swap unpack(Path zip, Path program) throws IOException {
        Path launcher = program.toAbsolutePath().normalize();
        Path target = launcher.getParent();
        if (target == null || target.getFileName() == null) {
            throw new IOException("The program is not in a folder of its own: " + launcher);
        }
        String name = target.getFileName().toString();
        Swap swap = new Swap(target, target.resolveSibling(name + ".update"), target.resolveSibling(name + ".previous"));
        delete(swap.staged());
        try (ZipFile file = new ZipFile(zip.toFile())) {
            String top = top(file, launcher.getFileName().toString());
            Files.createDirectories(swap.staged());
            Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/');
                if (!path.startsWith(top) || path.length() == top.length()) {
                    continue;
                }
                Path out = inside(swap.staged(), path.substring(top.length()), entry.getName());
                if (path.endsWith("/")) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (ZipException damaged) {
            delete(swap.staged());
            throw new IOException("The downloaded file is not a zip: " + damaged.getMessage(), damaged);
        } catch (IOException | RuntimeException failure) {
            delete(swap.staged());
            throw failure;
        }
        return swap;
    }

    /**
     * Where the program's folder starts in the zip: {@code ""} when the launcher is at the zip's top,
     * {@code "<folder>/"} when it is inside one folder there.
     */
    static String top(ZipFile file, String launcher) throws IOException {
        String wanted = launcher.toLowerCase(Locale.ROOT);
        List<String> tops = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements()) {
            String path = entries.nextElement().getName().replace('\\', '/');
            String[] parts = path.split("/", -1);
            if (parts.length <= 2 && parts[parts.length - 1].toLowerCase(Locale.ROOT).equals(wanted)) {
                tops.add(parts.length == 1 ? "" : parts[0] + "/");
            }
        }
        if (tops.size() != 1) {
            throw new IOException(tops.isEmpty()
                    ? "The zip does not hold " + launcher + ", so it is not this program's folder"
                    : "The zip holds " + launcher + " more than once: " + tops);
        }
        return tops.get(0);
    }

    /** A path in the zip, as a file under {@code folder}; never outside it. */
    private static Path inside(Path folder, String path, String published) throws IOException {
        try {
            Path out = folder.resolve(path).normalize();
            if (!out.startsWith(folder) || out.equals(folder)) {
                throw new IOException("The zip names a file outside its folder: " + published);
            }
            return out;
        } catch (InvalidPathException unusable) {
            throw new IOException("The zip names a file Windows cannot have: " + published, unusable);
        }
    }

    /** Removes a folder and everything in it; a folder that is not there is fine. */
    static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException gone) {
            // Removed meanwhile.
        }
    }
}
