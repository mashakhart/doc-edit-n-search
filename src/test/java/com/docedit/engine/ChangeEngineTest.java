package com.docedit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.docedit.exception.InvalidChangeException;
import com.docedit.exception.OverlappingChangesException;
import com.docedit.exception.RangeOutOfBoundsException;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import java.util.List;
import java.util.stream.Collectors;
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

    @Test
    void acceptRangeKeepsInsertionsAndRemovesStruckText() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        // The change spans struck "quick" [4,9) plus inserted "slow" [9,13).
        assertEquals("[U]the slow brown fox", render(ChangeEngine.acceptRange(redlined, 4, 13)));
    }

    @Test
    void rejectRangeRemovesInsertionsAndRestoresStruckText() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        assertEquals("[U]the quick brown fox", render(ChangeEngine.rejectRange(redlined, 4, 13)));
    }

    @Test
    void acceptRangeAffectsOnlyTheTargetedChange() {
        final List<Segment> redlined = apply("one two three", rangeChange(0, 3, "1"), rangeChange(8, 13, "3"));
        // Flattened "one1 two three3"; accept just the first change at [0,4).
        assertEquals("[U]1 two [D]three[I]3", render(ChangeEngine.acceptRange(redlined, 0, 4)));
    }

    @Test
    void rejectRangeAffectsOnlyTheTargetedChange() {
        final List<Segment> redlined = apply("one two three", rangeChange(0, 3, "1"), rangeChange(8, 13, "3"));
        // Flattened "one1 two three3"; reject just the second change at [9,15).
        assertEquals("[D]one[I]1[U] two three", render(ChangeEngine.rejectRange(redlined, 9, 15)));
    }

    @Test
    void acceptRangeOutOfBoundsIsRejected() {
        final List<Segment> redlined = apply("abc", rangeChange(0, 1, "X"));
        assertThrows(RangeOutOfBoundsException.class, () -> ChangeEngine.acceptRange(redlined, 0, 99));
    }

    @Test
    void acceptAllFinalizesEveryChange() {
        final List<Segment> redlined = apply("one two three", rangeChange(0, 3, "1"), rangeChange(8, 13, "3"));
        assertEquals("[U]1 two 3", render(ChangeEngine.acceptAll(redlined)));
    }

    @Test
    void rejectAllRevertsEveryChange() {
        final List<Segment> redlined = apply("one two three", rangeChange(0, 3, "1"), rangeChange(8, 13, "3"));
        assertEquals("[U]one two three", render(ChangeEngine.rejectAll(redlined)));
    }

    @Test
    void acceptingOnlyTheStruckPartLeavesTheInsertionPending() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        assertEquals("[U]the [I]slow[U] brown fox", render(ChangeEngine.acceptRange(redlined, 4, 9)));
    }

    @Test
    void rejectingOnlyTheInsertedPartLeavesTheDeletionPending() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        assertEquals("[U]the [D]quick[U] brown fox", render(ChangeEngine.rejectRange(redlined, 9, 13)));
    }

    @Test
    void acceptingOverUnchangedTextIsANoop() {
        final List<Segment> redlined = apply("the quick brown fox", rangeChange(4, 9, "slow"));
        assertEquals("[U]the [D]quick[I]slow[U] brown fox", render(ChangeEngine.acceptRange(redlined, 0, 4)));
    }

    @Test
    void acceptingAPureInsertionMakesItPermanent() {
        final List<Segment> inserted = apply("ac", rangeChange(1, 1, "b"));
        assertEquals("[U]abc", render(ChangeEngine.acceptRange(inserted, 1, 2)));
    }

    @Test
    void rejectingAPureDeletionRestoresIt() {
        final List<Segment> deleted = apply("hello world", rangeChange(5, 11, ""));
        assertEquals("[U]hello world", render(ChangeEngine.rejectRange(deleted, 5, 11)));
    }

    @Test
    void editSpanningAMultiSegmentDocumentTouchesOnlyTheTargetedRegion() {
        // Two redlines split the document into several segments; a later edit is
        // then located by binary search and splits only the segment it lands in.
        final List<Segment> redlined = apply("aaaa bbbb cccc dddd",
                rangeChange(0, 4, "1"), rangeChange(10, 14, "3"));
        assertEquals("1 bbbb 3 dddd", ChangeEngine.acceptedText(redlined));

        final String flattened = redlined.stream().map(Segment::text).collect(Collectors.joining());
        final int at = flattened.indexOf("dddd");
        final List<Segment> edited = ChangeEngine.apply(redlined, List.of(rangeChange(at, at + 4, "D4")));
        assertEquals("1 bbbb 3 D4", ChangeEngine.acceptedText(edited));
    }
}
