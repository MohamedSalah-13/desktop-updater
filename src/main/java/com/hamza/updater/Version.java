package com.hamza.updater;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A release's version: {@code 2.4.0}, {@code v2.4.0}, {@code 2.5.0-beta.1}. Up to four numbers, an optional
 * pre-release part after a dash, and build metadata after a plus that is ignored.
 *
 * <p>Ordered the semantic-versioning way: numbers first ({@code 2.10} is newer than {@code 2.9}, and
 * {@code 2.4} equals {@code 2.4.0}), and a pre-release is older than the release it leads to.
 */
public final class Version implements Comparable<Version> {

    private static final Pattern SHAPE =
            Pattern.compile("[vV]?(\\d{1,9}(?:\\.\\d{1,9}){0,3})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    private final int[] numbers;
    private final String preRelease;

    private Version(int[] numbers, String preRelease) {
        this.numbers = numbers;
        this.preRelease = preRelease;
    }

    /** The version a text says, or empty when it is not one. */
    public static Optional<Version> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = SHAPE.matcher(text.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int[] numbers = Arrays.stream(matcher.group(1).split("\\.")).mapToInt(Integer::parseInt).toArray();
        int length = numbers.length;
        while (length > 1 && numbers[length - 1] == 0) {
            length--;
        }
        String preRelease = matcher.group(2) == null ? "" : matcher.group(2);
        return Optional.of(new Version(Arrays.copyOf(numbers, length), preRelease));
    }

    /** @throws IllegalArgumentException when the text is not a version */
    public static Version of(String text) {
        return parse(text).orElseThrow(() -> new IllegalArgumentException("Not a version: " + text));
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    public boolean isNewerThan(Version other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(Version other) {
        int length = Math.max(numbers.length, other.numbers.length);
        for (int i = 0; i < length; i++) {
            int mine = i < numbers.length ? numbers[i] : 0;
            int theirs = i < other.numbers.length ? other.numbers[i] : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), other.preRelease.isEmpty());
        }
        return comparePreRelease(preRelease.split("\\."), other.preRelease.split("\\."));
    }

    private static int comparePreRelease(String[] mine, String[] theirs) {
        for (int i = 0; i < Math.min(mine.length, theirs.length); i++) {
            boolean myNumber = mine[i].matches("\\d{1,9}");
            boolean theirNumber = theirs[i].matches("\\d{1,9}");
            int order;
            if (myNumber && theirNumber) {
                order = Integer.compare(Integer.parseInt(mine[i]), Integer.parseInt(theirs[i]));
            } else if (myNumber != theirNumber) {
                order = myNumber ? -1 : 1;
            } else {
                order = mine[i].compareTo(theirs[i]);
            }
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(mine.length, theirs.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version version
                && Arrays.equals(numbers, version.numbers) && preRelease.equals(version.preRelease);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(numbers) + preRelease.hashCode();
    }

    /** Without the leading {@code v}, and with at least three numbers: {@code 2.4.0}. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.max(3, numbers.length); i++) {
            if (i > 0) {
                text.append('.');
            }
            text.append(i < numbers.length ? numbers[i] : 0);
        }
        return preRelease.isEmpty() ? text.toString() : text.append('-').append(preRelease).toString();
    }
}
