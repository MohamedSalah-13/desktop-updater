package com.hamza.updater;

/** What one look for an update came to. */
public sealed interface CheckResult {

    /**
     * A release newer than the running program.
     *
     * @param required the running version is older than the release's {@link Release#minimum}: the
     *                 publisher says it must not be skipped
     */
    record Available(Release release, Version current, boolean required) implements CheckResult {
    }

    /** Nothing newer is published. */
    record UpToDate(Version current) implements CheckResult {
    }

    /**
     * The program does not know its own version - it runs from an IDE or a bare jar, not from an installed
     * copy - so it is never told of a release.
     */
    record NotInstalled() implements CheckResult {
    }

    /** @param reason for a log; no connection, the server down, an answer that is not a release */
    record Failed(String reason) implements CheckResult {
    }
}
