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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.stereotype.Component;

/**
 * In-memory inverted index over document titles and text, kept in sync by the
 * store on every write. Matching is order-independent and partial: a document is
 * a hit if any of its words CONTAINS a query token (so "appl" matches "apple").
 * Results are returned in a stable id order and deliberately NOT ranked by
 * similarity.
 *
 * Trade-off: mapping tokens to document ids means a query scans the token
 * vocabulary (far smaller than the documents themselves) rather than every
 * document's text. Substring matching makes it a vocabulary scan instead of a
 * single hash lookup; a suffix-automaton or n-gram index would restore near-O(1)
 * lookup at the cost of more memory. Re-indexing on every write trades write cost
 * for fast reads — the right trade when reads dominate.
 */
@Component
public class SearchIndex {

    private static final Pattern TOKEN = Pattern.compile("\\w+");
    private static final int CONTEXT_CHARS = 40;

    private final Map<String, Set<String>> postings = new ConcurrentHashMap<>();  // token -> doc ids
    private final Map<String, String> texts = new ConcurrentHashMap<>();          // doc id -> text
    private final Map<String, String> titles = new ConcurrentHashMap<>();         // doc id -> title
    private final Map<String, Set<String>> docTokens = new ConcurrentHashMap<>(); // doc id -> its tokens

    /** Builds or rebuilds the index entries for a document. */
    public void index(@Nonnull final String docId, @Nullable final String title, @Nonnull final String text) {
        remove(docId);
        final String safeTitle = title == null ? "" : title;
        texts.put(docId, text);
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
        titles.remove(docId);
    }

    /**
     * Returns documents whose title or text contains a query token (partial words
     * count), each with a snippet around a match. Order-independent; an empty query
     * yields no results.
     */
    @Nonnull
    public List<SearchResult> search(@Nonnull final String query) {
        final Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        final Set<String> matched = new TreeSet<>();  // stable id order, not by similarity
        for (final Map.Entry<String, Set<String>> entry : postings.entrySet()) {
            if (containsAny(entry.getKey(), queryTokens)) {
                matched.addAll(entry.getValue());
            }
        }
        final List<SearchResult> results = new ArrayList<>(matched.size());
        for (final String docId : matched) {
            results.add(new SearchResult(docId, titles.get(docId), snippet(texts.get(docId), queryTokens)));
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
    private static String snippet(@Nonnull final String text, @Nonnull final Set<String> queryTokens) {
        final int match = firstMatchPosition(text, queryTokens);
        if (match < 0) {  // matched only via the title
            return text.length() > 2 * CONTEXT_CHARS ? text.substring(0, 2 * CONTEXT_CHARS) + "…" : text;
        }
        final int start = Math.max(0, match - CONTEXT_CHARS);
        final int end = Math.min(text.length(), match + CONTEXT_CHARS);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    private static int firstMatchPosition(@Nonnull final String text, @Nonnull final Set<String> queryTokens) {
        final String lower = text.toLowerCase(Locale.ROOT);
        int earliest = -1;
        for (final String queryToken : queryTokens) {
            final int position = lower.indexOf(queryToken);
            if (position >= 0 && (earliest < 0 || position < earliest)) {
                earliest = position;
            }
        }
        return earliest;
    }

    private static boolean containsAny(@Nonnull final String token, @Nonnull final Set<String> queryTokens) {
        for (final String queryToken : queryTokens) {
            if (token.contains(queryToken)) {
                return true;
            }
        }
        return false;
    }
}
