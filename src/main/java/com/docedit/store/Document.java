package com.docedit.store;

import com.docedit.engine.Segment;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A stored document: its redline segments plus identity and version. Immutable —
 * an edit produces a new Document with the next version, so instances are safe to
 * share across threads and never appear half-updated.
 */
public record Document(String id, @Nullable String title, List<Segment> segments, long version) {

    /** Returns a copy with new segments and the version incremented by one. */
    @Nonnull
    Document withSegments(@Nonnull final List<Segment> newSegments) {
        return new Document(id, title, newSegments, version + 1);
    }
}
