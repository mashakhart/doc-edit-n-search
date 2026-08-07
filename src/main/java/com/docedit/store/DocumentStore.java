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
import java.util.function.UnaryOperator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 */
@Repository
public class DocumentStore {

    private final ConcurrentMap<String, Document> documents = new ConcurrentHashMap<>();
    private final SearchIndex searchIndex;

    public DocumentStore(final SearchIndex searchIndex) {
        this.searchIndex = searchIndex;
    }

    /** Creates and stores a new document at version 1; a null text becomes empty. */
    @Nonnull
    public Document create(@Nullable final String text, @Nullable final String title) {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Document document =
                new Document(id, title, ChangeEngine.fromText(text == null ? "" : text), 1L);
        documents.put(id, document);
        reindex(document);
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
        searchIndex.remove(id);
    }

    /**
     * Atomically transforms a document's segments and returns the new version.
     * Rejects a stale expectedVersion and leaves the document untouched if the
     * transform throws.
     */
    @Nonnull
    private Document mutate(@Nonnull final String id, @Nullable final Long expectedVersion,
                            @Nonnull final UnaryOperator<List<Segment>> transform) {
        final Document updated = documents.compute(id, (key, current) -> {
            if (current == null) {
                throw new DocumentNotFoundException(id);
            }
            if (expectedVersion != null && expectedVersion != current.version()) {
                throw new VersionConflictException(expectedVersion, current.version());
            }
            return current.withSegments(transform.apply(current.segments()));
        });
        reindex(updated);
        return updated;
    }

    /** Keeps the search index in sync with a document's current accepted text. */
    private void reindex(@Nonnull final Document document) {
        searchIndex.index(document.id(), document.title(), ChangeEngine.acceptedText(document.segments()));
    }
}
