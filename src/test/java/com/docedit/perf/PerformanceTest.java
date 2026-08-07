package com.docedit.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docedit.engine.ChangeEngine;
import com.docedit.engine.Segment;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import com.docedit.payload.response.SearchResult;
import com.docedit.search.SearchIndex;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Large-file performance benchmarks. Thresholds are deliberately generous so the
 * tests assert "near-linear, not accidentally quadratic" rather than a precise
 * time, to stay stable across machines.
 */
class PerformanceTest {

    private static String repeatUntil(final String unit, final int minLength) {
        final StringBuilder builder = new StringBuilder(minLength + unit.length());
        while (builder.length() < minLength) {
            builder.append(unit);
        }
        return builder.toString();
    }

    @Test
    void searchOverATenMegabyteDocumentIsFast() {
        final String big = repeatUntil("the quick brown fox jumps over the lazy dog. ", 10_000_000)
                + " needlexyzzy";
        final SearchIndex index = new SearchIndex();
        index.index("big", "Large", big);   // indexing happens once, outside the timed search

        final List<SearchResult> results = assertTimeoutPreemptively(
                Duration.ofSeconds(3), () -> index.search("needlexyzzy"));
        assertEquals(1, results.size());
        assertTrue(results.get(0).snippet().contains("needlexyzzy"));
    }

    @Test
    void editingAOneMegabyteDocumentIsNearLinear() {
        final String big = repeatUntil("lorem ipsum dolor sit amet. ", 1_000_000);
        final List<Segment> segments = ChangeEngine.fromText(big);
        final Change change = new Change(null, new Range(0, 5), "LOREM");

        final List<Segment> edited = assertTimeoutPreemptively(
                Duration.ofSeconds(10), () -> ChangeEngine.apply(segments, List.of(change)));
        assertTrue(ChangeEngine.acceptedText(edited).startsWith("LOREM ipsum"));
    }
}
