package com.docedit.search;

import com.docedit.payload.response.SearchResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory inverted index over document titles and text, kept in sync by the
 * store on every write. Matching is order-independent and prefix-based: a document
 * is a hit if any of its words STARTS WITH a query token (so "appl" matches
 * "apple", but "ppl" does not). Results are returned in a stable id order and
 * deliberately NOT ranked by similarity.
 *
 * The postings map is a sorted ConcurrentSkipListMap, so a prefix query is an
 * O(log V + matches) range scan of the token vocabulary — jump to the first token
 * >= the prefix and stop at the first that no longer starts with it — rather than
 * scanning every token or every document. Supporting arbitrary substring matching
 * (not just prefixes) would instead want an n-gram or suffix-automaton index, at
 * the cost of more memory. Re-indexing on every write trades write cost for fast
 * reads — the right trade when reads dominate.
 *
 * Threading contract: writes (index/remove) must be serialised by a SINGLE thread
 * — the store submits them to a single-thread executor — while search may run
 * concurrently on many threads. The maps are concurrent so a search never corrupts
 * or is corrupted by the writer; it may only briefly miss a document that is
 * mid-re-index. remove()'s check-then-act relies on that single-writer assumption.
 */
@Component
public class SearchIndex {

    private static final Pattern TOKEN = Pattern.compile("\\w+");
    private static final int CONTEXT_CHARS = 40;

    // Sorted token -> doc ids, so prefix queries are a range scan (see class doc).
    private final ConcurrentNavigableMap<String, Set<String>> postings = new ConcurrentSkipListMap<>();
    private final Map<String, String> texts = new ConcurrentHashMap<>();          // doc id -> text
    private final Map<String, String> loweredTexts = new ConcurrentHashMap<>();   // doc id -> lowercased text
    private final Map<String, String> titles = new ConcurrentHashMap<>();         // doc id -> title
    private final Map<String, Set<String>> docTokens = new ConcurrentHashMap<>(); // doc id -> its tokens

    /** Builds or rebuilds the index entries for a document. */
    public void index(@Nonnull final String docId, @Nullable final String title, @Nonnull final String text) {
        remove(docId);
        final String safeTitle = title == null ? "" : title;
        texts.put(docId, text);
        // Cache the lowercased text once at index time so snippet lookups don't
        // re-lowercase the whole document on every search.
        loweredTexts.put(docId, text.toLowerCase(Locale.ROOT));
        titles.put(docId, safeTitle);
        final Set<String> tokens = tokenize(safeTitle + " " + text);
        for (final String token : tokens) {
            postings.computeIfAbsent(token, key -> ConcurrentHashMap.newKeySet()).add(docId);
        }
        docTokens.put(docId, tokens);
    }

    /** Drops all index entries for a document (used before re-indexing and on delete). */
    public void remove(@Nonnull final String docId) {
        final Set<String> tokens = docTokens.remove(docId);
        if (tokens != null) {
            for (final String token : tokens) {
                final Set<String> ids = postings.get(token);
                if (ids != null) {
                    ids.remove(docId);
                    if (ids.isEmpty()) {
                        postings.remove(token);
                    }
                }
            }
        }
        texts.remove(docId);
        loweredTexts.remove(docId);
        titles.remove(docId);
    }

    /**
     * Returns documents that have a word starting with a query token (prefix match),
     * across title and text, each with a snippet around a match. Order-independent;
     * an empty query yields no results.
     */
    @Nonnull
    public List<SearchResult> search(@Nonnull final String query) {
        final Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        final Set<String> matched = new TreeSet<>();  // stable id order, not by similarity
        for (final String prefix : queryTokens) {
            // Sorted vocabulary: start at the first token >= the prefix and stop as
            // soon as one no longer starts with it (they form a contiguous block).
            for (final Map.Entry<String, Set<String>> entry : postings.tailMap(prefix).entrySet()) {
                if (!entry.getKey().startsWith(prefix)) {
                    break;
                }
                matched.addAll(entry.getValue());
            }
        }
        final List<SearchResult> results = new ArrayList<>(matched.size());
        for (final String docId : matched) {
            final String text = texts.get(docId);
            final String loweredText = loweredTexts.get(docId);
            if (text == null || loweredText == null) {
                // The document was removed or is mid-re-index on the index thread
                // between the postings scan and here; skip it (eventual consistency).
                continue;
            }
            results.add(new SearchResult(docId, titles.get(docId), snippet(text, loweredText, queryTokens)));
        }
        return results;
    }

    @Nonnull
    private static Set<String> tokenize(@Nonnull final String text) {
        final Set<String> tokens = new HashSet<>();
        final Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    @Nonnull
    private static String snippet(@Nonnull final String text, @Nonnull final String loweredText,
                                  @Nonnull final Set<String> queryTokens) {
        final int match = firstMatchPosition(loweredText, queryTokens);
        if (match < 0) {  // matched only via the title
            return text.length() > 2 * CONTEXT_CHARS ? text.substring(0, 2 * CONTEXT_CHARS) + "…" : text;
        }
        final int start = Math.max(0, match - CONTEXT_CHARS);
        final int end = Math.min(text.length(), match + CONTEXT_CHARS);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    /** Earliest position of any query token in the already-lowercased text, or -1. */
    private static int firstMatchPosition(@Nonnull final String loweredText, @Nonnull final Set<String> queryTokens) {
        int earliest = -1;
        for (final String queryToken : queryTokens) {
            final int position = loweredText.indexOf(queryToken);
            if (position >= 0 && (earliest < 0 || position < earliest)) {
                earliest = position;
            }
        }
        return earliest;
    }
}
