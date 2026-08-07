package com.docedit.payload.response;

import com.docedit.engine.Segment;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The document as returned to clients: the redline segments to render, the
 * accepted-changes text for convenience, and the version (which is also the ETag).
 */
public record DocumentResponse(
        String id,
        @Nullable String title,
        List<Segment> segments,
        String acceptedText,
        long version) {
}
