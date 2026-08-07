package com.docedit.store;

import com.docedit.engine.ChangeEngine;
import com.docedit.exception.DocumentNotFoundException;
import com.docedit.exception.VersionConflictException;
import com.docedit.payload.request.Change;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.stereotype.Repository;

/**
 * In-memory document store and the only component that mutates document state.
 *
 * A ConcurrentHashMap keyed by id is the backing store, and each document carries
 * a monotonic version that powers ETag / If-Match optimistic concurrency. Edits
 * run inside compute, which is atomic per key: the version check, engine call, and
 * version bump either all succeed together or, if any step throws, leave the
 * existing document untouched. A real deployment would swap this for a database;
 * the method surface is kept narrow so that stays localised.
 */
@Repository
public class DocumentStore {

    private final ConcurrentMap<String, Document> documents = new ConcurrentHashMap<>();

    /** Creates and stores a new document at version 1; a null text becomes empty. */
    @Nonnull
    public Document create(@Nullable final String text, @Nullable final String title) {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Document document =
                new Document(id, title, ChangeEngine.fromText(text == null ? "" : text), 1L);
        documents.put(id, document);
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

    /**
     * Atomically applies a batch of changes and returns the updated document.
     * Rejects a stale expectedVersion and leaves the document untouched if the
     * engine rejects the batch.
     */
    @Nonnull
    public Document edit(@Nonnull final String id, @Nonnull final List<Change> changes,
                         @Nullable final Long expectedVersion) {
        return documents.compute(id, (key, current) -> {
            if (current == null) {
                throw new DocumentNotFoundException(id);
            }
            if (expectedVersion != null && expectedVersion != current.version()) {
                throw new VersionConflictException(expectedVersion, current.version());
            }
            return current.withSegments(ChangeEngine.apply(current.segments(), changes));
        });
    }

    /** Removes the document, or throws DocumentNotFoundException if the id is unknown. */
    public void delete(@Nonnull final String id) {
        if (documents.remove(id) == null) {
            throw new DocumentNotFoundException(id);
        }
    }
}
