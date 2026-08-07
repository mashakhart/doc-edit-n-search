package com.docedit.exception;

/** Thrown when an If-Match version does not match the current one (HTTP 412). */
public final class VersionConflictException extends RuntimeException {

    /** Builds a message showing the expected and current versions. */
    public VersionConflictException(final long expected, final long actual) {
        super("version conflict: expected " + expected + ", current is " + actual);
    }

    /** Skips stack-trace capture: an expected client error, not a server bug. */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
