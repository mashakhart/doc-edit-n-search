package com.docedit.store;

import com.docedit.engine.ChangeEngine;
import com.docedit.engine.Segment;
import com.docedit.exception.DocumentNotFoundException;
import com.docedit.exception.VersionConflictException;
import com.docedit.payload.request.Change;
import com.docedit.payload.request.Range;
import com.docedit.search.SearchIndex;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

/**
 * In-memory document store and the only component that mutates document state.
 *
 * A ConcurrentHashMap keyed by id is the backing store, and each document carries
 * a monotonic version that powers ETag / If-Match optimistic concurrency. Every
 * mutation runs through mutate(), which uses compute for per-key atomicity: the
 * version check, transform, and version bump either all succeed together or, if
 * any step throws, leave the existing document untouched. A real deployment would
 * swap this for a database; the method surface is kept narrow so that stays
 * localised.
 *
 * Design trade-off: the store also drives the search index — it re-indexes on
 * every write, but off the request thread via a single-thread executor — which
 * couples storage to search. That keeps the index consistent with no extra wiring
 * (and keeps the O(n) tokenizing off the write's critical path, so search may lag
 * a write by a beat); if this grew, the store could instead publish change events
 * and let the index subscribe, decoupling the two.
 */
@Repository
public class DocumentStore {

    private final ConcurrentMap<String, Document> documents = new ConcurrentHashMap<>();
    private final SearchIndex searchIndex;
    private final Executor searchIndexExecutor;

    public DocumentStore(final SearchIndex searchIndex,
                         @Qualifier("searchIndexExecutor") final Executor searchIndexExecutor) {
        this.searchIndex = searchIndex;
        this.searchIndexExecutor = searchIndexExecutor;
    }

    /** Creates and stores a new document at version 1; a null text becomes empty. */
    @Nonnull
    public Document create(@Nullable final String text, @Nullable final String title) {
        final List<Segment> segments = ChangeEngine.fromText(text == null ? "" : text);
        String id;
        Document document;
        // putIfAbsent returns non-null if the id is already taken; on the (astronomically
        // rare) UUID collision, regenerate rather than overwrite an existing document.
        do {
            id = UUID.randomUUID().toString().replace("-", "");
            document = new Document(id, title, segments, 1L);
        } while (documents.putIfAbsent(id, document) != null);
        reindexAsync(document);
        return document;
    }

    /** Returns the document, or throws DocumentNotFoundException if the id is unknown. */
    @Nonnull
    public Document get(@Nonnull final String id) {
        final Document document = documents.get(id);
        if (document == null) {
            throw new DocumentNotFoundException(id);
        }
        return document;
    }

    /** Returns an immutable snapshot of all stored documents. */
    @Nonnull
    public List<Document> list() {
        return List.copyOf(documents.values());
    }

    /** Applies a batch of changes as a redline. */
    @Nonnull
    public Document edit(@Nonnull final String id, @Nonnull final List<Change> changes,
                         @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion, segments -> ChangeEngine.apply(segments, changes));
    }

    /**
     * Applies an ordered stream of edits sequentially — each against the result of
     * the previous — as one versioned write. This is the interactive editor's path:
     * the client renders keystrokes optimistically and then replays, in order, the
     * ones it made since its last sync. Applying them one at a time (rather than as
     * an atomic batch resolved against a single base, like edit) lets later edits
     * build on earlier ones, which is exactly what a stream of keystrokes does.
     */
    @Nonnull
    public Document editStream(@Nonnull final String id, @Nonnull final List<Change> changes,
                               @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion, segments -> {
            List<Segment> current = segments;
            for (final Change change : changes) {
                current = ChangeEngine.apply(current, List.of(change));
            }
            return current;
        });
    }

    /** Accepts the changes within the given range: insertions kept, struck text removed. */
    @Nonnull
    public Document acceptChanges(@Nonnull final String id, @Nonnull final Range range,
                                  @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion,
                segments -> ChangeEngine.acceptRange(segments, range.start(), range.end()));
    }

    /** Rejects the changes within the given range: insertions removed, struck text restored. */
    @Nonnull
    public Document rejectChanges(@Nonnull final String id, @Nonnull final Range range,
                                  @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion,
                segments -> ChangeEngine.rejectRange(segments, range.start(), range.end()));
    }

    /** Accepts every change in the document. */
    @Nonnull
    public Document acceptAllChanges(@Nonnull final String id, @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion, ChangeEngine::acceptAll);
    }

    /** Rejects every change in the document. */
    @Nonnull
    public Document rejectAllChanges(@Nonnull final String id, @Nullable final Long expectedVersion) {
        return mutate(id, expectedVersion, ChangeEngine::rejectAll);
    }

    /** Removes the document, or throws DocumentNotFoundException if the id is unknown. */
    public void delete(@Nonnull final String id) {
        if (documents.remove(id) == null) {
            throw new DocumentNotFoundException(id);
        }
        searchIndexExecutor.execute(() -> searchIndex.remove(id));
    }

    /**
     * Atomically transforms a document's segments and returns the new version.
     * Rejects a stale expectedVersion and leaves the document untouched if the
     * transform throws.
     */
    @Nonnull
    private Document mutate(@Nonnull final String id, @Nullable final Long expectedVersion,
                            @Nonnull final UnaryOperator<List<Segment>> transform) {
        return documents.compute(id, (key, current) -> {
            if (current == null) {
                throw new DocumentNotFoundException(id);
            }
            if (expectedVersion != null && expectedVersion != current.version()) {
                throw new VersionConflictException(expectedVersion, current.version());
            }
            final Document updated = current.withSegments(transform.apply(current.segments()));
            // Submit the re-index inside compute so tasks enqueue in version order;
            // the single-thread executor then applies them in that order. Submitting
            // afterwards could enqueue a newer write's re-index before an older one.
            reindexAsync(updated);
            return updated;
        });
    }

    /**
     * Re-indexes a document off the request thread. The task is submitted while the
     * per-document lock is held (inside compute, or right after create), so tasks
     * enqueue in version order; the single-thread executor applies them in that
     * order. The O(n) acceptedText + tokenizing runs on the executor thread, not
     * the caller's.
     */
    private void reindexAsync(@Nonnull final Document document) {
        searchIndexExecutor.execute(
                () -> searchIndex.index(document.id(), document.title(),
                        ChangeEngine.acceptedText(document.segments())));
    }
}
