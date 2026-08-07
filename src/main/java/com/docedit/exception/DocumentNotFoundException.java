package com.docedit.exception;

/** Thrown when a document id is not present (mapped to HTTP 404). */
public final class DocumentNotFoundException extends RuntimeException {

    /** Builds a message naming the missing document id. */
    public DocumentNotFoundException(final String id) {
        super("document '" + id + "' not found");
    }

    /** Skips stack-trace capture: an expected client error, not a server bug. */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
