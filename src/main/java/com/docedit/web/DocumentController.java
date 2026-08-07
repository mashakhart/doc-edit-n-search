package com.docedit.web;

import com.docedit.engine.ChangeEngine;
import com.docedit.payload.request.ChangeRequest;
import com.docedit.payload.request.DocumentCreate;
import com.docedit.payload.request.Range;
import com.docedit.payload.response.DocumentResponse;
import com.docedit.store.Document;
import com.docedit.store.DocumentStore;
import jakarta.validation.Valid;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for documents. Handlers stay thin: they parse the request,
 * call the store, and shape the response. Reads and writes are separated by HTTP
 * method, a document is a REST resource, and editing is a PATCH carrying an
 * atomic batch of changes. The document version is returned as an ETag, and a
 * PATCH may carry If-Match to make the write conditional.
 */
@RestController
@RequestMapping("/documents")
public class DocumentController {

    private final DocumentStore store;

    public DocumentController(final DocumentStore store) {
        this.store = store;
    }

    /** Creates a document and returns it with a 201 and its ETag. */
    @PostMapping
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody final DocumentCreate body) {
        return withEtag(HttpStatus.CREATED, store.create(body.text(), body.title()));
    }

    /** Lists all documents. */
    @GetMapping
    public List<DocumentResponse> list() {
        return store.list().stream().map(DocumentController::toResponse).toList();
    }

    /** Returns one document (404 if absent) with its current ETag. */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> get(@PathVariable final String id) {
        return withEtag(HttpStatus.OK, store.get(id));
    }

    /** Applies a batch of changes; If-Match makes the write conditional (412 on mismatch). */
    @PatchMapping("/{id}")
    public ResponseEntity<DocumentResponse> edit(
            @PathVariable final String id,
            @Valid @RequestBody final ChangeRequest body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable final String ifMatch) {
        return withEtag(HttpStatus.OK, store.edit(id, body.changes(), parseIfMatch(ifMatch)));
    }

    /** Deletes a document, returning 204 (404 if absent). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable final String id) {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Accepts the changes in the given range: insertions become permanent, struck text removed. */
    @PostMapping("/{id}/accept")
    public ResponseEntity<DocumentResponse> accept(
            @PathVariable final String id,
            @Valid @RequestBody final Range range,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable final String ifMatch) {
        return withEtag(HttpStatus.OK, store.acceptChanges(id, range, parseIfMatch(ifMatch)));
    }

    /** Rejects the changes in the given range: insertions removed, struck text restored. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<DocumentResponse> reject(
            @PathVariable final String id,
            @Valid @RequestBody final Range range,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable final String ifMatch) {
        return withEtag(HttpStatus.OK, store.rejectChanges(id, range, parseIfMatch(ifMatch)));
    }

    /** Accepts every change in the document. */
    @PostMapping("/{id}/accept-all")
    public ResponseEntity<DocumentResponse> acceptAll(
            @PathVariable final String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable final String ifMatch) {
        return withEtag(HttpStatus.OK, store.acceptAllChanges(id, parseIfMatch(ifMatch)));
    }

    /** Rejects every change in the document. */
    @PostMapping("/{id}/reject-all")
    public ResponseEntity<DocumentResponse> rejectAll(
            @PathVariable final String id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable final String ifMatch) {
        return withEtag(HttpStatus.OK, store.rejectAllChanges(id, parseIfMatch(ifMatch)));
    }

    /** Parses an If-Match header (e.g. "3" or W/"3") into a version, or null if unusable. */
    @Nullable
    private static Long parseIfMatch(@Nullable final String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        final String cleaned = ifMatch.strip().replaceFirst("^W/", "").replace("\"", "").strip();
        if (cleaned.isEmpty() || !cleaned.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Long.valueOf(cleaned);
    }

    /** Wraps a document in a response carrying its version as the ETag header. */
    @Nonnull
    private static ResponseEntity<DocumentResponse> withEtag(final HttpStatus status, final Document document) {
        return ResponseEntity.status(status)
                .eTag("\"" + document.version() + "\"")
                .body(toResponse(document));
    }

    /** Maps the internal document to its response representation (segments + accepted text). */
    @Nonnull
    private static DocumentResponse toResponse(final Document document) {
        return new DocumentResponse(
                document.id(),
                document.title(),
                document.segments(),
                ChangeEngine.acceptedText(document.segments()),
                document.version());
    }
}
