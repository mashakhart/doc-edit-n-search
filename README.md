# doc-edit-n-search

A document **redlining** and **search** service. Documents are edited as tracked
changes — new text shows up inserted, removed text is struck through but kept
visible — and each change can be individually **accepted** or **rejected**.
Documents are searchable across their title and text content.

Java 21 · Spring Boot 3 · Gradle · JUnit 5, plus a small vanilla-JS front end.

---

## Quick start

Requirements: **JDK 21**.

```bash
./gradlew test        # run the test suite
./gradlew bootRun     # start the app on http://localhost:8080
```

Open <http://localhost:8080> for the UI (a redline editor with an accept/reject
panel and a Library search box). The API is under `/documents`.

---

## The redline model

A document is stored as an ordered list of **segments**, each a run of text with
one of three states:

| Type | Meaning | Rendered as |
|---|---|---|
| `UNCHANGED` | original, accepted text | black |
| `INSERTED` | newly added text | blue |
| `DELETED` | original text marked for removal, kept visible | red, struck through |

An edit is a **range replace**: replace characters `[start, end)` with new text.
The engine turns that into a redline — it strikes the old span (`DELETED`) and
inserts the new text (`INSERTED`) right after it. Special cases fall out of the
same rule:

- **Insert** — a zero-width range (`start == end`) → only `INSERTED`.
- **Delete** — an empty replacement → only `DELETED` (struck).
- **Edit your own insertion** — stays inserted (it was never original).
- **Delete your own insertion** — removed cleanly, not struck.

`acceptedText` is the document with all changes accepted (everything except
deletions); it's returned alongside the segments for convenience.

Ranges are measured against the **flattened** text the client displays —
struck characters included — so the frontend's cursor offset maps directly to a
range.

---

## API

All responses are JSON. A document response looks like:

```json
{
  "id": "…", "title": "Demo", "version": 2,
  "acceptedText": "the slow brown fox",
  "segments": [
    { "text": "the ", "type": "UNCHANGED" },
    { "text": "quick", "type": "DELETED" },
    { "text": "slow", "type": "INSERTED" },
    { "text": " brown fox", "type": "UNCHANGED" }
  ]
}
```

Errors use a uniform body: `{ "error": "...", "code": 422 }`.

### Documents

```bash
# Create
curl -X POST localhost:8080/documents \
  -H 'Content-Type: application/json' \
  -d '{"text":"the quick brown fox","title":"Demo"}'

# List (lightweight summaries: id, title, preview) / get one (full document)
curl localhost:8080/documents
curl localhost:8080/documents/{id}

# Edit: redline "quick" -> "slow" (replace flattened range [4,9))
curl -X PATCH localhost:8080/documents/{id} \
  -H 'Content-Type: application/json' \
  -d '{"changes":[{"range":{"start":4,"end":9},"replacement":"slow"}]}'

# Bulk: several changes in one atomic request
curl -X PATCH localhost:8080/documents/{id} \
  -H 'Content-Type: application/json' \
  -d '{"changes":[
        {"range":{"start":0,"end":3},"replacement":"1"},
        {"range":{"start":8,"end":13},"replacement":"3"}
      ]}'

# Optimistic concurrency: only apply if the version matches (else 412)
curl -X PATCH localhost:8080/documents/{id} \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' \
  -d '{"changes":[{"range":{"start":4,"end":9},"replacement":"slow"}]}'

# Delete
curl -X DELETE localhost:8080/documents/{id}
```

The response carries the new version in an `ETag` header (e.g. `ETag: "2"`),
which you pass back as `If-Match` on the next write.

### Accept / reject changes

Accept or reject the change spanning a flattened range; or all at once.

```bash
# Accept one change (its span covers the struck old text + the inserted new text)
curl -X POST localhost:8080/documents/{id}/accept \
  -H 'Content-Type: application/json' -d '{"start":4,"end":13}'

# Reject one change
curl -X POST localhost:8080/documents/{id}/reject \
  -H 'Content-Type: application/json' -d '{"start":4,"end":13}'

# Accept / reject every change in the document
curl -X POST localhost:8080/documents/{id}/accept-all
curl -X POST localhost:8080/documents/{id}/reject-all
```

### Search

```bash
curl "localhost:8080/documents/search?q=contract%20fox"
```

```json
{
  "query": "contract fox",
  "results": [
    { "docId": "…", "title": "Demo", "snippet": "…the quick brown fox…" }
  ]
}
```

Matching is **order-independent** and **partial**: a document is a hit if any of
its words contains a query token (so `appl` matches `apple`), across its title
and text. Results are returned in a stable order and are deliberately **not**
ranked by similarity. Each hit includes a snippet around a match.

---

## Architecture

Layered; each layer depends only on the one below it.

```
HTTP  ─►  web/            DocumentController, SearchController, ApiExceptionHandler
          store/          DocumentStore  (state, versioning, concurrency)
          engine/         ChangeEngine   (pure redline logic)   search/  SearchIndex
          payload/        request + response records            exception/  typed errors
```

A `PATCH` flows: `DocumentController` validates the body → `DocumentStore.edit`
takes a per-document atomic lock, checks the version, and calls
`ChangeEngine.apply` → the engine produces new segments → the store bumps the
version and re-indexes the text for search → the controller returns the document
with a fresh `ETag`. Any thrown exception is mapped to `{error, code}` by
`ApiExceptionHandler`, keeping the store and engine free of HTTP concerns.

---

## Design decisions

- **Range-based edits (not find-and-replace).** Edits originate from a frontend
  that has the document loaded, so it always knows exact offsets. This drops the
  "which occurrence?" ambiguity entirely; a find/replace tool could be layered on
  top later.
- **Redline as typed segments.** The backend describes *what* changed
  (`UNCHANGED`/`INSERTED`/`DELETED`); the frontend decides *how* to show it. This
  keeps presentation out of the API and makes the engine trivial to unit-test.
- **Accept/reject by range.** A change is identified by its flattened span, which
  the frontend already knows from the rendered segments — no per-change IDs
  needed for a first version.
- **Optimistic concurrency.** A monotonic `version` backs `ETag`/`If-Match`;
  conflicting concurrent writes are detected and rejected (412) rather than
  silently clobbering. True concurrent *merging* (Operational Transform / CRDTs)
  is a deliberate non-goal here — it's the natural next step for real-time
  multi-user editing.
- **Inverted index for search.** `token → doc ids`, rebuilt for a document on
  every write. Partial (substring) matching means a query scans the token
  *vocabulary* — far smaller than the documents themselves — and unions the
  matching posting sets, instead of scanning every document's text.
- **API shape.** Reads (`GET`) and writes (`POST`/`PATCH`/`DELETE`) are cleanly
  separated; documents are a REST resource; editing is a `PATCH` of an atomic
  batch. Accept/reject and search are genuinely action/query shaped, so they're
  `POST`/`GET` sub-resources rather than being forced into pure REST.

---

## Performance considerations

- **Search scales.** Indexing a document is a single linear tokenizing pass on
  write. Because matching is partial (substring), a query scans the token
  *vocabulary* (far smaller than the corpus text) and unions posting sets, rather
  than scanning every document; exact-token matching would instead be a direct
  hash lookup. The `PerformanceTest` searches a **10 MB** document well within its
  budget.
- **Editing is near-linear in time.** `ChangeEngine.apply` resolves ranges
  against the original, rejects overlaps, and rebuilds the text in one pass — all
  `O(n)`.
- **Editing's memory trade-off.** The redline engine flattens to one cell per
  character while applying, which is `O(n)` memory. That's fine for typical
  documents (the benchmark edits a **1 MB** doc comfortably), but for true 10 MB+
  editing the next step would be a piece-table / rope representation that tracks
  spans instead of characters. This is a conscious simplicity-vs-scale trade for
  this iteration.
- **Trade-off, stated plainly.** The index costs roughly the corpus size again in
  memory and a re-index on each write, to make reads fast. That's the right trade
  when reads dominate writes — as they do for a search service.

---

## Testing

```bash
./gradlew test
```

- **Engine** — redline behavior (insert/delete/replace, edit vs. delete of your
  own insertion, layered edits), overlap/bounds rejection, and accept/reject
  (individual, partial, all, no-op).
- **Search index** — order-independence, title + text matching, partial
  (substring) matching, case-insensitivity, snippets, removal, re-index.
- **API** (`MockMvc`) — every endpoint, including `{error, code}` bodies, 404 /
  412 / 422 paths.
- **Real documents** — `SampleDocumentTest` (engine + index) and
  `SampleWorkflowTest` (full HTTP: create → search → redline → re-index → accept)
  run against prose stored in `src/test/resources/samples` (opening chapters of
  *Harry Potter* and *The Stranger*).
- **Performance** — `PerformanceTest` exercises a 10 MB search and a 1 MB edit.

---

## Requirements coverage

| Requirement | Where |
|---|---|
| Change requests (JSON → updated doc) | `PATCH /documents/{id}` |
| Search (snippets) | `GET /documents/search` |
| Unit tests incl. large-file/perf | `*/**Test`, `PerformanceTest` |
| Sample requests | curl examples above |
| README (setup, usage, perf, rationale) | this file |
| Error handling (4xx/5xx `{error,code}`) | `ApiExceptionHandler` |
| Bulk operations | `changes: [...]`, applied atomically |
| Performance / near-linear | inverted index; engine `O(n)` |
| Concurrency (versioning/ETag) | `version` + `If-Match` → 412 |
| In-memory inverted index + trade-offs | `SearchIndex`, above |
| API design (read/write split, REST vs. action) | above |

---

## Possible next steps

Per-change IDs (accept/reject without sending a span), search pagination
(`limit`/`offset`) and per-document search, a piece-table engine for very large
documents, persistence, auth, and Operational Transform for real-time multi-user
editing.
