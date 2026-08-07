package com.docedit.exception;

/**
 * Base type for change failures caused by invalid client input (mapped to HTTP
 * 4xx by the web layer). Kept distinct from server-side failures so the
 * exception handler never has to guess whether a 4xx or 5xx is appropriate.
 */
public abstract class ChangeException extends RuntimeException {

    /** Creates the exception with a self-describing, client-facing message. */
    protected ChangeException(final String message) {
        super(message);
    }

    /**
     * Skips capturing a stack trace: these signal invalid input rather than
     * server bugs, so the (expensive) trace would carry no useful information.
     */
    @Override
    public final Throwable fillInStackTrace() {
        return this;
    }
}
