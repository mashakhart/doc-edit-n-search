package com.docedit.engine;

/** The redline state of a run of text. */
public enum SegmentType {
    /** Original text, unchanged. */
    UNCHANGED,
    /** Newly added text (shown blue, not struck). */
    INSERTED,
    /** Original text marked for removal (shown red, struck through). */
    DELETED
}
