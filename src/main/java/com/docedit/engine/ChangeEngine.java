package com.docedit.engine;

import com.docedit.exception.InvalidChangeException;
import com.docedit.exception.OverlappingChangesException;
import com.docedit.exception.RangeOutOfBoundsException;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, stateless redline engine over a document's segment list, with
 * all-or-nothing batch semantics.
 *
 * A "replace" strikes the old span (marks it DELETED, kept visible) and inserts
 * the new text (INSERTED) right after it; a zero-width range is a pure insertion;
 * an empty replacement is a pure deletion. Deleting text that was itself an
 * insertion drops it (never original, so nothing to strike). Ranges are measured
 * against the flattened text — struck characters included — which is exactly what
 * the frontend displays.
 *
 * Edits work directly on segments: the range is located with a binary search over
 * the segments' cumulative offsets, and only the segments the range touches are
 * split and retyped. So an edit is O(log s + affected) in the number of segments
 * rather than O(n) in the document length. Every range is resolved against the
 * ORIGINAL coordinates, overlapping edits are rejected, and the result is rebuilt
 * left-to-right. The class holds no state and no Spring or I/O dependencies.
 */
public final class ChangeEngine {

    private static final String REPLACE_OPERATION = "replace";

    // How a segment's type changes when a range operation covers it; null = drop it.
    private static final Retype IDENTITY = type -> type;
    private static final Retype STRIKE = type -> type == SegmentType.INSERTED ? null : SegmentType.DELETED;
    private static final Retype ACCEPT = type -> type == SegmentType.DELETED ? null : SegmentType.UNCHANGED;
    private static final Retype REJECT = type -> type == SegmentType.INSERTED ? null : SegmentType.UNCHANGED;

    private ChangeEngine() {
    }

    /** Wraps plain text as a single unchanged segment; empty text yields no segments. */
    @Nonnull
    public static List<Segment> fromText(@Nonnull final String text) {
        return text.isEmpty() ? List.of() : List.of(new Segment(text, SegmentType.UNCHANGED));
    }

    /**
     * Applies the changes to the segments and returns the new redlined segments.
     * Throws InvalidChangeException, RangeOutOfBoundsException, or
     * OverlappingChangesException on invalid input.
     */
    @Nonnull
    public static List<Segment> apply(@Nonnull final List<Segment> segments,
                                      @Nonnull final List<Change> changes) {
        if (changes.isEmpty()) {
            return segments;
        }
        final int[] offsets = cumulativeOffsets(segments);
        final int total = offsets[offsets.length - 1];
        final List<Span> spans = new ArrayList<>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            spans.add(resolve(changes.get(i), i, total));
        }
        spans.sort((a, b) -> Integer.compare(a.start(), b.start()));
        rejectOverlaps(spans);
        final List<Segment> result = new ArrayList<>();
        int cursor = 0;
        for (final Span span : spans) {
            emit(result, segments, offsets, cursor, span.start(), IDENTITY);
            emit(result, segments, offsets, span.start(), span.end(), STRIKE);
            if (!span.replacement().isEmpty()) {
                result.add(new Segment(span.replacement(), SegmentType.INSERTED));
            }
            cursor = span.end();
        }
        emit(result, segments, offsets, cursor, total, IDENTITY);
        return coalesce(result);
    }

    /** The document text if all changes were accepted: everything except deletions. */
    @Nonnull
    public static String acceptedText(@Nonnull final List<Segment> segments) {
        return concatExcluding(segments, SegmentType.DELETED);
    }

    /**
     * Accepts the changes within the range: insertions become permanent (UNCHANGED)
     * and struck text is removed. Text outside the range is untouched.
     */
    @Nonnull
    public static List<Segment> acceptRange(@Nonnull final List<Segment> segments,
                                            final int start, final int end) {
        return rangeOp(segments, start, end, ACCEPT);
    }

    /**
     * Rejects the changes within the range: insertions are removed and struck text
     * is restored (UNCHANGED). Text outside the range is untouched.
     */
    @Nonnull
    public static List<Segment> rejectRange(@Nonnull final List<Segment> segments,
                                            final int start, final int end) {
        return rangeOp(segments, start, end, REJECT);
    }

    /** Accepts every change in the document (accept over the whole span). */
    @Nonnull
    public static List<Segment> acceptAll(@Nonnull final List<Segment> segments) {
        return acceptRange(segments, 0, totalLength(segments));
    }

    /** Rejects every change in the document (reject over the whole span). */
    @Nonnull
    public static List<Segment> rejectAll(@Nonnull final List<Segment> segments) {
        return rejectRange(segments, 0, totalLength(segments));
    }

    /** Retypes the segments within a range, leaving everything outside it untouched. */
    @Nonnull
    private static List<Segment> rangeOp(@Nonnull final List<Segment> segments, final int start, final int end,
                                         @Nonnull final Retype transform) {
        final int[] offsets = cumulativeOffsets(segments);
        final int total = offsets[offsets.length - 1];
        if (start < 0 || end < start || end > total) {
            throw new RangeOutOfBoundsException(start, end, total);
        }
        final List<Segment> result = new ArrayList<>();
        emit(result, segments, offsets, 0, start, IDENTITY);
        emit(result, segments, offsets, start, end, transform);
        emit(result, segments, offsets, end, total, IDENTITY);
        return coalesce(result);
    }

    /**
     * Appends the pieces of the original segments covering flattened [from, to),
     * retyped by transform (a null result drops the piece). The first segment is
     * found by binary search; only the touched segments are split.
     */
    private static void emit(@Nonnull final List<Segment> result, @Nonnull final List<Segment> segments,
                             @Nonnull final int[] offsets, final int from, final int to,
                             @Nonnull final Retype transform) {
        if (from >= to) {
            return;
        }
        int position = from;
        int index = segmentIndexAt(offsets, from);
        while (position < to && index < segments.size()) {
            final int segmentStart = offsets[index];
            final int segmentEnd = offsets[index + 1];
            final int pieceStart = Math.max(position, segmentStart);
            final int pieceEnd = Math.min(to, segmentEnd);
            if (pieceEnd > pieceStart) {
                final SegmentType newType = transform.apply(segments.get(index).type());
                if (newType != null) {
                    final String text =
                            segments.get(index).text().substring(pieceStart - segmentStart, pieceEnd - segmentStart);
                    result.add(new Segment(text, newType));
                }
            }
            position = pieceEnd;
            index++;
        }
    }

    /** Binary search: the index of the segment containing the given flattened offset. */
    private static int segmentIndexAt(@Nonnull final int[] offsets, final int offset) {
        int low = 0;
        int high = offsets.length - 1;
        while (low < high) {
            final int mid = (low + high + 1) >>> 1;
            if (offsets[mid] <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    @Nonnull
    private static Span resolve(@Nonnull final Change change, final int index, final int length) {
        final String operation = change.operation() == null ? REPLACE_OPERATION : change.operation();
        if (!REPLACE_OPERATION.equalsIgnoreCase(operation)) {
            throw new InvalidChangeException(
                    "change[" + index + "] unsupported operation: '" + operation + "'");
        }
        final Range range = change.range();
        if (range == null) {
            throw new InvalidChangeException("change[" + index + "] must specify a range");
        }
        final int start = range.start();
        final int end = range.end();
        if (start < 0 || end < start || end > length) {
            throw new RangeOutOfBoundsException(index, start, end, length);
        }
        final String replacement = change.replacement() == null ? "" : change.replacement();
        return new Span(start, end, replacement);
    }

    private static void rejectOverlaps(@Nonnull final List<Span> ascendingByStart) {
        for (int i = 1; i < ascendingByStart.size(); i++) {
            final Span previous = ascendingByStart.get(i - 1);
            final Span current = ascendingByStart.get(i);
            if (current.start() < previous.end()) {
                throw new OverlappingChangesException(
                        previous.start(), previous.end(), current.start(), current.end());
            }
        }
    }

    /** Prefix sums of segment lengths; offsets[i] is the flattened start of segment i. */
    @Nonnull
    private static int[] cumulativeOffsets(@Nonnull final List<Segment> segments) {
        final int[] offsets = new int[segments.size() + 1];
        for (int i = 0; i < segments.size(); i++) {
            offsets[i + 1] = offsets[i] + segments.get(i).text().length();
        }
        return offsets;
    }

    private static int totalLength(@Nonnull final List<Segment> segments) {
        int length = 0;
        for (final Segment segment : segments) {
            length += segment.text().length();
        }
        return length;
    }

    /** Merges consecutive same-type segments into one; returns an immutable list. */
    @Nonnull
    private static List<Segment> coalesce(@Nonnull final List<Segment> segments) {
        final List<Segment> out = new ArrayList<>();
        for (final Segment segment : segments) {
            if (!out.isEmpty() && out.get(out.size() - 1).type() == segment.type()) {
                final Segment previous = out.remove(out.size() - 1);
                out.add(new Segment(previous.text() + segment.text(), segment.type()));
            } else {
                out.add(segment);
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    private static String concatExcluding(@Nonnull final List<Segment> segments,
                                          @Nonnull final SegmentType excluded) {
        final StringBuilder builder = new StringBuilder();
        for (final Segment segment : segments) {
            if (segment.type() != excluded) {
                builder.append(segment.text());
            }
        }
        return builder.toString();
    }

    /** Maps a covered segment's type to its new type, or null to drop it. */
    @FunctionalInterface
    private interface Retype {
        @Nullable
        SegmentType apply(@Nonnull SegmentType type);
    }

    /** A resolved edit in flattened coordinates. */
    private record Span(int start, int end, String replacement) {
    }
}
