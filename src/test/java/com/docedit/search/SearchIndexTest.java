package com.docedit.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docedit.payload.response.SearchResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchIndexTest {

    private SearchIndex index;

    @BeforeEach
    void setUp() {
        index = new SearchIndex();
        index.index("d1", "Master Agreement", "The parties agree to the contract and indemnity.");
        index.index("d2", "Lease", "This lease covers the property and monthly rent.");
        index.index("d3", "NDA", "Confidential information between the parties.");
    }

    private List<String> ids(final String query) {
        return index.search(query).stream().map(SearchResult::docId).toList();
    }

    @Test
    void matchesByTextContent() {
        assertEquals(List.of("d1"), ids("contract"));
    }

    @Test
    void matchingIsOrderIndependent() {
        assertEquals(List.of("d1"), ids("indemnity contract"));
        assertEquals(ids("contract indemnity"), ids("indemnity contract"));
    }

    @Test
    void anySharedWordSurfacesTheDocument() {
        // Both d1 and d3 contain "parties"; d2 does not.
        assertEquals(List.of("d1", "d3"), ids("parties"));
    }

    @Test
    void matchesByTitle() {
        // "agreement" appears only in d1's title.
        assertEquals(List.of("d1"), ids("agreement"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertEquals(List.of("d1"), ids("CONTRACT"));
    }

    @Test
    void partialWordMatchesTheContainingWord() {
        final SearchIndex fresh = new SearchIndex();
        fresh.index("fruit", "Fruit", "I ate an apple today.");
        assertEquals(1, fresh.search("appl").size());   // prefix of "apple"
        assertEquals(1, fresh.search("ppl").size());    // infix of "apple"
        assertEquals(1, fresh.search("apple").size());  // whole word still matches
        assertEquals(0, fresh.search("xyz").size());    // unrelated fragment does not
    }

    @Test
    void snippetContainsTheMatch() {
        final SearchResult hit = index.search("contract").get(0);
        assertTrue(hit.snippet().toLowerCase().contains("contract"));
    }

    @Test
    void noSharedWordReturnsNothing() {
        assertTrue(index.search("zzzznomatch").isEmpty());
    }

    @Test
    void blankQueryReturnsNothing() {
        assertTrue(index.search("   ").isEmpty());
    }

    @Test
    void removedDocumentNoLongerMatches() {
        index.remove("d1");
        assertTrue(index.search("contract").isEmpty());
    }

    @Test
    void reindexingReplacesOldContent() {
        index.index("d2", "Lease", "totally different words now");
        assertFalse(ids("property").contains("d2"));  // old word gone
        assertTrue(ids("different").contains("d2"));   // new word present
    }
}
