package com.docedit.payload.response;

import com.docedit.engine.Segment;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The document as returned to clients: the redline segments to render, the
 * accepted-changes text for convenience, and the version (which is also the ETag).
 *
 * Design trade-off: this reuses the engine's Segment type directly instead of a
 * separate response DTO. It avoids a mapping layer, at the cost of the payload
 * package depending on the engine package; a dedicated response Segment would
 * restore a clean one-way layer boundary.
 */
public record DocumentResponse(
        String id,
        @Nullable String title,
        List<Segment> segments,
        String acceptedText,
        long version) {
}
