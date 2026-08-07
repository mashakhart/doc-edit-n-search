package com.docedit.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docedit.engine.ChangeEngine;
import com.docedit.engine.Segment;
import com.docedit.engine.SegmentType;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import com.docedit.payload.response.SearchResult;
import com.docedit.search.SearchIndex;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the engine and search index against real prose stored in the repo
 * (src/test/resources/samples), rather than only tiny synthetic strings.
 */
class SampleDocumentTest {

    private static final String HARRY_POTTER = Samples.HARRY_POTTER;
    private static final String THE_STRANGER = Samples.THE_STRANGER;

    private SearchIndex index;

    @BeforeEach
    void setUp() {
        index = new SearchIndex();
        index.index("hp", "Harry Potter", HARRY_POTTER);
        index.index("ts", "The Stranger", THE_STRANGER);
    }

    private List<String> ids(final String query) {
        return index.search(query).stream().map(SearchResult::docId).toList();
    }

    @Test
    void wordUniqueToOneDocumentMatchesOnlyThatDocument() {
        assertEquals(List.of("hp"), ids("Dursley"));
        assertEquals(List.of("ts"), ids("Marengo"));
        assertEquals(List.of("ts"), ids("mother"));
    }

    @Test
    void aSharedWordMatchesBothDocuments() {
        assertEquals(List.of("hp", "ts"), ids("the"));
    }

    @Test
    void searchOverRealTextIsOrderIndependent() {
        assertEquals(ids("Dursley Grunnings"), ids("Grunnings Dursley"));
        assertEquals(List.of("hp"), ids("Grunnings Dursley"));
    }

    @Test
    void snippetShowsContextAroundTheMatchNotTheWholeDocument() {
        final SearchResult hit = index.search("Grunnings").get(0);
        assertTrue(hit.snippet().contains("Grunnings"));
        assertTrue(hit.snippet().length() < HARRY_POTTER.length());
    }

    @Test
    void redlineEditOnARealDocumentStrikesOldAndInsertsNew() {
        final int at = HARRY_POTTER.indexOf("Grunnings");
        final Change change = new Change(null, new Range(at, at + "Grunnings".length()), "Acme");
        final List<Segment> redlined = ChangeEngine.apply(ChangeEngine.fromText(HARRY_POTTER), List.of(change));

        assertTrue(redlined.stream().anyMatch(s -> s.type() == SegmentType.DELETED && s.text().equals("Grunnings")));
        assertTrue(redlined.stream().anyMatch(s -> s.type() == SegmentType.INSERTED && s.text().equals("Acme")));

        final String accepted = ChangeEngine.acceptedText(redlined);
        assertTrue(accepted.contains("a firm called Acme, which made drills"));
        assertFalse(accepted.contains("Grunnings"));
    }

    @Test
    void bulkEditOnRealTextResolvesEachRangeAgainstTheOriginal() {
        // Two replacements in ONE batch, at different offsets. Because each range
        // is resolved against the original, the first edit changing length must not
        // shift where the second lands.
        final int drills = HARRY_POTTER.indexOf("drills");
        final int mustache = HARRY_POTTER.indexOf("mustache");
        final List<Change> changes = List.of(
                new Change(null, new Range(mustache, mustache + "mustache".length()), "beard"),
                new Change(null, new Range(drills, drills + "drills".length()), "widgets"));

        final String accepted = ChangeEngine.acceptedText(
                ChangeEngine.apply(ChangeEngine.fromText(HARRY_POTTER), changes));

        assertTrue(accepted.contains("which made widgets"));
        assertTrue(accepted.contains("very large beard"));
        assertFalse(accepted.contains("drills"));
        assertFalse(accepted.contains("mustache"));
    }

    @Test
    void rejectingAllChangesRestoresTheExactOriginalDocument() {
        final int mother = THE_STRANGER.indexOf("MOTHER");
        final List<Segment> redlined = ChangeEngine.apply(
                ChangeEngine.fromText(THE_STRANGER),
                List.of(new Change(null, new Range(mother, mother + "MOTHER".length()), "FATHER")));
        assertTrue(ChangeEngine.acceptedText(redlined).contains("FATHER died today"));

        final List<Segment> restored = ChangeEngine.rejectAll(redlined);
        assertEquals(THE_STRANGER, ChangeEngine.acceptedText(restored));
    }

}
