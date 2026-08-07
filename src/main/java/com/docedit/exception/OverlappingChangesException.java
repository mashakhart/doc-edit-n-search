package com.docedit.exception;

/** Thrown when two edits in the same batch touch overlapping character spans. */
public final class OverlappingChangesException extends ChangeException {

    /** Builds a message showing the two spans that overlap. */
    public OverlappingChangesException(
            final int firstStart, final int firstEnd, final int secondStart, final int secondEnd) {
        super("changes overlap: [" + firstStart + ", " + firstEnd + ") and ["
                + secondStart + ", " + secondEnd + ")");
    }
}
