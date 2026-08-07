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
    void prefixMatchesTheWordButInfixDoesNot() {
        final SearchIndex fresh = new SearchIndex();
        fresh.index("fruit", "Fruit", "I ate an apple today.");
        assertEquals(1, fresh.search("appl").size());   // prefix of "apple"
        assertEquals(1, fresh.search("apple").size());  // the whole word is a prefix of itself
        assertEquals(0, fresh.search("ppl").size());    // infix is NOT a prefix
        assertEquals(0, fresh.search("xyz").size());    // unrelated fragment does not
    }

    @Test
    void prefixSurfacesEveryWordWithThatPrefixAndNoOthers() {
        final SearchIndex fresh = new SearchIndex();
        fresh.index("a", "A", "contract");
        fresh.index("b", "B", "contractor signed");
        fresh.index("c", "C", "contraption");
        // "contract" is a prefix of "contract" and "contractor", but not "contraption".
        assertEquals(List.of("a", "b"), ids(fresh, "contract"));
        // "contr" is a prefix of all three; the range scan must stop before unrelated tokens.
        assertEquals(List.of("a", "b", "c"), ids(fresh, "contr"));
        assertTrue(fresh.search("contz").isEmpty());
    }

    private static List<String> ids(final SearchIndex index, final String query) {
        return index.search(query).stream().map(SearchResult::docId).toList();
    }

    @Test
    void snippetContainsTheMatch() {
        final SearchResult hit = index.search("contract").get(0);
        assertTrue(hit.snippet().toLowerCase().contains("contract"));
    }

    @Test
    void snippetUsesOriginalCasingButMatchesCaseInsensitively() {
        // The snippet is located via the cached lowercased text but sliced from the
        // original, so a lowercase query still finds an uppercase word and the
        // snippet keeps the document's casing.
        final SearchIndex fresh = new SearchIndex();
        fresh.index("d", "Doc", "The CONTRACT was signed today.");
        assertTrue(fresh.search("contract").get(0).snippet().contains("CONTRACT"));
    }

    @Test
    void snippetReflectsNewTextAfterReindex() {
        // Guards the lowercased-text cache from going stale: re-indexing must
        // refresh it so the snippet is drawn from the new text, not the old.
        final SearchIndex fresh = new SearchIndex();
        fresh.index("d", "Doc", "the original clause about widgets");
        fresh.index("d", "Doc", "a revised clause about gadgets");
        final String snippet = fresh.search("clause").get(0).snippet();
        assertTrue(snippet.contains("gadgets"));
        assertFalse(snippet.contains("widgets"));
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
