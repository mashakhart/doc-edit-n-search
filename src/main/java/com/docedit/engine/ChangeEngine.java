package com.docedit.engine;

import com.docedit.exception.InvalidChangeException;
import com.docedit.exception.OverlappingChangesException;
import com.docedit.exception.RangeOutOfBoundsException;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Pure, stateless redline engine. Applies a batch of range-based edits to a
 * document's segments and returns the new redlined segments, with all-or-nothing
 * semantics.
 *
 * A "replace" strikes the old span (marks it DELETED, kept visible) and inserts
 * the new text (INSERTED) right after it; a zero-width range is a pure insertion;
 * an empty replacement is a pure deletion. Deleting text that was itself an
 * insertion simply drops it (it was never original, so there is nothing to
 * strike). Ranges are measured against the flattened text — struck characters
 * included — which is exactly what the frontend displays.
 *
 * Every range is resolved against the ORIGINAL coordinates, overlapping edits are
 * rejected, and edits are applied left-to-right while reconstructing a fresh cell
 * list, so offsets never shift mid-apply. The class holds no state and has no
 * Spring or I/O dependencies, so it is unit-testable and thread-safe.
 */
public final class ChangeEngine {

    private static final String REPLACE_OPERATION = "replace";

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
        final List<Cell> cells = flatten(segments);
        if (changes.isEmpty()) {
            return coalesce(cells);
        }
        final List<Span> spans = new ArrayList<>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            spans.add(resolve(changes.get(i), i, cells.size()));
        }
        spans.sort((a, b) -> Integer.compare(a.start(), b.start()));
        rejectOverlaps(spans);
        final List<Cell> out = new ArrayList<>(cells.size());
        int pos = 0;
        for (final Span span : spans) {
            out.addAll(cells.subList(pos, span.start()));
            for (final Cell cell : cells.subList(span.start(), span.end())) {
                if (cell.type() != SegmentType.INSERTED) {
                    out.add(new Cell(cell.character(), SegmentType.DELETED));
                }
            }
            for (int c = 0; c < span.replacement().length(); c++) {
                out.add(new Cell(span.replacement().charAt(c), SegmentType.INSERTED));
            }
            pos = span.end();
        }
        out.addAll(cells.subList(pos, cells.size()));
        return coalesce(out);
    }

    /** The document text if all changes were accepted: everything except deletions. */
    @Nonnull
    public static String acceptedText(@Nonnull final List<Segment> segments) {
        final StringBuilder builder = new StringBuilder();
        for (final Segment segment : segments) {
            if (segment.type() != SegmentType.DELETED) {
                builder.append(segment.text());
            }
        }
        return builder.toString();
    }

    /** Validates one change and resolves its range against the flattened length. */
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

    /** Rejects the batch if any two spans (sorted by start) overlap. */
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

    /** Expands segments into one cell per character, carrying each character's state. */
    @Nonnull
    private static List<Cell> flatten(@Nonnull final List<Segment> segments) {
        final List<Cell> cells = new ArrayList<>();
        for (final Segment segment : segments) {
            for (int i = 0; i < segment.text().length(); i++) {
                cells.add(new Cell(segment.text().charAt(i), segment.type()));
            }
        }
        return cells;
    }

    /** Merges consecutive same-state cells back into segments. */
    @Nonnull
    private static List<Segment> coalesce(@Nonnull final List<Cell> cells) {
        final List<Segment> segments = new ArrayList<>();
        final StringBuilder run = new StringBuilder();
        SegmentType runType = null;
        for (final Cell cell : cells) {
            if (runType != null && cell.type() != runType) {
                segments.add(new Segment(run.toString(), runType));
                run.setLength(0);
            }
            run.append(cell.character());
            runType = cell.type();
        }
        if (runType != null) {
            segments.add(new Segment(run.toString(), runType));
        }
        return segments;
    }

    // One character plus its redline state — the working unit during application.
    private record Cell(char character, SegmentType type) {
    }

    // A resolved edit in flattened coordinates.
    private record Span(int start, int end, String replacement) {
    }
}
