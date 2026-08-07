package com.docedit.payload.response;

import java.util.List;

/** The results of a document search, echoing the query for convenience. */
public record SearchResponse(String query, List<SearchResult> results) {
}
