package com.docedit.exception;

/** Thrown when a change is structurally invalid: a missing range or an unsupported operation. */
public final class InvalidChangeException extends ChangeException {

    /** Creates the exception with a message describing what was invalid. */
    public InvalidChangeException(final String message) {
        super(message);
    }
}
