package com.docedit.web;

import com.docedit.payload.response.SearchResponse;
import com.docedit.search.SearchIndex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only search endpoint, kept separate from the document CRUD controller.
 * A query matches documents whose title or text contains a query word; partial
 * words count (e.g. "appl" matches "apple"), and order does not matter.
 */
@RestController
public class SearchController {

    private final SearchIndex index;

    public SearchController(final SearchIndex index) {
        this.index = index;
    }

    /** Searches documents by title and text; matches share any query word (order-independent). */
    @GetMapping("/documents/search")
    public SearchResponse search(@RequestParam("q") final String q) {
        return new SearchResponse(q, index.search(q));
    }
}
