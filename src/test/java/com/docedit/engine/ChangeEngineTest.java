package com.docedit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docedit.exception.InvalidChangeException;
import com.docedit.exception.OverlappingChangesException;
import com.docedit.exception.RangeOutOfBoundsException;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeEngineTest {

    private static Change rangeChange(final int start, final int end, final String replacement) {
        return new Change(null, new Range(start, end), replacement);
    }

    /** Compact view of segments for assertions, e.g. "[U]the [D]quick[I]slow". */
    private static String render(final List<Segment> segments) {
        final StringBuilder builder = new StringBuilder();
        for (final Segment segment : segments) {
            builder.append('[').append(segment.type().name().charAt(0)).append(']').append(segment.text());
        }
        return builder.toString();
    }

    private static List<Segment> apply(final String text, final Change... changes) {
        return ChangeEngine.apply(ChangeEngine.fromText(text), List.of(changes));
    }

    @Test
    void fromTextIsASingleUnchangedSegment() {
        assertEquals("[U]hello", render(ChangeEngine.fromText("hello")));
    }

    @Test
    void replaceStrikesOldTextAndInsertsNew() {
        final List<Segment> result = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        assertEquals("[U]the [D]quick[I]slow[U] brown fox", render(result));
        assertEquals("the slow brown fox", ChangeEngine.acceptedText(result));
    }

    @Test
    void zeroWidthRangeInsertsWithoutStriking() {
        assertEquals("[U]a[I]b[U]c", render(apply("ac", rangeChange(1, 1, "b"))));
    }

    @Test
    void emptyReplacementStrikesWithoutInserting() {
        assertEquals("[U]hello[D] world", render(apply("hello world", rangeChange(5, 11, ""))));
    }

    @Test
    void removingInsertedTextDropsItWithoutStriking() {
        final List<Segment> inserted = apply("ac", rangeChange(1, 1, "b")); // a[I]b c
        final List<Segment> result = ChangeEngine.apply(inserted, List.of(rangeChange(1, 2, "")));
        assertEquals("[U]ac", render(result));
        assertEquals("ac", ChangeEngine.acceptedText(result));
    }

    @Test
    void modifyingInsertedTextStaysInserted() {
        final List<Segment> inserted = apply("ac", rangeChange(1, 1, "b")); // a[I]b c
        final List<Segment> result = ChangeEngine.apply(inserted, List.of(rangeChange(1, 2, "B")));
        assertEquals("[U]a[I]B[U]c", render(result));
        assertEquals("aBc", ChangeEngine.acceptedText(result));
    }

    @Test
    void batchIsResolvedAgainstOriginalCoordinates() {
        final List<Segment> result = apply("one two three", rangeChange(8, 13, "3"), rangeChange(0, 3, "1"));
        assertEquals("[D]one[I]1[U] two [D]three[I]3", render(result));
        assertEquals("1 two 3", ChangeEngine.acceptedText(result));
    }

    @Test
    void layeredEditUsesDisplayedCoordinatesIncludingStruckText() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        // Flattened display is "the quickslow brown fox"; strike " brown" at [13, 19).
        final List<Segment> result = ChangeEngine.apply(redlined, List.of(rangeChange(13, 19, "")));
        assertEquals("the slow fox", ChangeEngine.acceptedText(result));
    }

    @Test
    void zeroWidthRangeAtEndAppends() {
        assertEquals("[U]abc[I]d", render(apply("abc", rangeChange(3, 3, "d"))));
    }

    @Test
    void rangeSpanningTheWholeTextIsAllowed() {
        assertEquals("[D]abc[I]X", render(apply("abc", rangeChange(0, 3, "X"))));
    }

    @Test
    void emptyBatchLeavesSegmentsUnchanged() {
        assertEquals("[U]abc", render(apply("abc")));
    }

    @Test
    void overlappingChangesAreRejected() {
        assertThrows(
                OverlappingChangesException.class,
                () -> apply("abcdef", rangeChange(1, 4, "X"), rangeChange(2, 5, "Y")));
    }

    @Test
    void rangeOutOfBoundsIsRejected() {
        assertThrows(RangeOutOfBoundsException.class, () -> apply("abc", rangeChange(0, 99, "X")));
    }

    @Test
    void rangeWithEndBeforeStartIsRejected() {
        assertThrows(RangeOutOfBoundsException.class, () -> apply("abcdef", rangeChange(4, 2, "X")));
    }

    @Test
    void missingRangeIsRejected() {
        final Change change = new Change(null, null, "x");
        assertThrows(InvalidChangeException.class, () -> ChangeEngine.apply(ChangeEngine.fromText("abc"), List.of(change)));
    }

    @Test
    void unsupportedOperationIsRejected() {
        final Change change = new Change("strikethrough", new Range(0, 1), "x");
        assertThrows(InvalidChangeException.class, () -> ChangeEngine.apply(ChangeEngine.fromText("abc"), List.of(change)));
    }
}
