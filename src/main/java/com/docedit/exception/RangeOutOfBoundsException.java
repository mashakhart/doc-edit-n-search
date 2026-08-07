package com.docedit.exception;

/** Thrown when a position range falls outside the document's bounds. */
public final class RangeOutOfBoundsException extends ChangeException {

    /** Builds a message showing the offending range and the document length. */
    public RangeOutOfBoundsException(final int index, final int start, final int end, final int length) {
        super("change[" + index + "]: range [" + start + ", " + end + ") out of bounds for length " + length);
    }

    /** Builds a message for a range not tied to a specific change (e.g. accept/reject). */
    public RangeOutOfBoundsException(final int start, final int end, final int length) {
        super("range [" + start + ", " + end + ") out of bounds for length " + length);
    }
}
