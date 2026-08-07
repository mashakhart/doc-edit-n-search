package com.docedit.payload.response;

import javax.annotation.Nullable;

/** One search hit: the matching document and a snippet of its text around a match. */
public record SearchResult(String docId, @Nullable String title, String snippet) {
}
