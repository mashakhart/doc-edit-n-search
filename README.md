# Contract Redlining Tool

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
panel and a Library search box). All HTTP endpoints live under `/documents` — see
the [API](#api) section below.

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

# Interactive editor: replay a stream of keystroke edits applied IN ORDER, each
# building on the previous (this is what the browser editor posts). Here: append
# "s" (making "cats"), then "!" at index 4 — valid only because the first ran.
curl -X POST localhost:8080/documents/{id}/edits \
  -H 'Content-Type: application/json' \
  -d '{"changes":[
        {"range":{"start":3,"end":3},"replacement":"s"},
        {"range":{"start":4,"end":4},"replacement":"!"}
      ]}'

# Delete
curl -X DELETE localhost:8080/documents/{id}
```

`PATCH` and `POST /{id}/edits` differ deliberately: `PATCH` applies a batch of
**independent** edits atomically, all resolved against the current text (the bulk
API); `/edits` applies an **ordered stream** sequentially, so later edits build on
earlier ones — exactly how a run of keystrokes behaves.

Every response carries the document's version as its `ETag` header (e.g.
`ETag: "2"`). An ETag is just HTTP's opaque "which version is this?" tag — here it
*is* the version number. Send it back as `If-Match` on your next write and the
server applies the change only if the version still matches, otherwise **412**
(see [Concurrency & thread safety](#concurrency--thread-safety)).

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

Matching is **order-independent** and **prefix-based**: a document is a hit if any
of its words starts with a query token (so `appl` matches `apple`, but `ppl` does
not), across its title and text. The token vocabulary is kept sorted (a
`ConcurrentSkipListMap`), so a prefix query is an `O(log V + matches)` range scan
rather than a full scan. Results are in a stable order, deliberately **not** ranked
by similarity, each with a snippet around a match.

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
version and schedules a search re-index off the request thread → the controller
returns the document with a fresh `ETag`. The interactive editor instead posts to
`POST /documents/{id}/edits`, which applies an ordered stream of edits
sequentially. Any thrown exception is mapped to `{error, code}` by
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
- **Two write paths.** `PATCH /documents/{id}` applies a batch of *independent*
  edits atomically, resolved against the current text (the bulk API). The
  interactive editor posts to `/documents/{id}/edits`, an *ordered stream* applied
  sequentially so later keystrokes build on earlier ones.
- **Optimistic, client-authoritative editing.** The browser applies each keystroke
  locally and renders it instantly — a small mirror of the engine's single
  range-replace — then debounce-batches the edits to `/edits` and re-syncs from the
  response. Typing never waits on a round-trip; the server stays authoritative and
  corrects any divergence on the next flush.
- **Indexing off the write path.** Re-indexing runs on a single-thread executor, so
  the `O(n)` tokenizing never blocks a write. The task is submitted under the
  per-document lock, so re-indexes stay ordered with versions; search is thus
  *eventually* consistent (it may lag a write by a beat).
- **Optimistic concurrency.** A monotonic `version` backs `ETag`/`If-Match`;
  conflicting concurrent writes are detected and rejected (412) rather than
  silently clobbering. True concurrent *merging* (Operational Transform / CRDTs)
  is a deliberate non-goal here — it's the natural next step for real-time
  multi-user editing.
- **Inverted index for search.** `token → doc ids`, rebuilt for a document on
  every write and kept in a sorted `ConcurrentSkipListMap`. Prefix matching is then
  an `O(log V + matches)` range scan over the token *vocabulary* (far smaller than
  the documents), unioning the matching posting sets, instead of scanning every
  document's text.
- **API shape.** Reads (`GET`) and writes (`POST`/`PATCH`/`DELETE`) are cleanly
  separated; documents are a REST resource; editing is a `PATCH` of an atomic
  batch. Accept/reject and search are genuinely action/query shaped, so they're
  `POST`/`GET` sub-resources rather than being forced into pure REST.

---

## Concurrency & thread safety

The server handles each HTTP request on its own thread, so the store can be hit
concurrently. Safety rests on three things:

- **One mutation choke point.** Every write goes through `DocumentStore.mutate`,
  which uses `ConcurrentHashMap.compute(id, …)`. `compute` runs its function
  atomically for that key, so the version check, the edit, and the version bump are
  one indivisible step — two edits to the *same* document can't interleave and lose
  an update.
- **Immutable documents.** A `Document` is an immutable record holding an immutable
  segment list (the engine returns `List.copyOf(...)` / `List.of(...)`). An edit
  builds a brand-new `Document` and swaps it in wholesale, so a concurrent reader
  sees either the old document or the new one in full — never a half-updated one.
- **Optimistic version checks across requests.** The per-key lock only spans a
  single call; to catch conflicts *between* requests, a write may carry `If-Match`
  with the version it last saw. A stale version is rejected with 412 rather than
  clobbering a newer write.

The search index is written only by the single-thread re-index executor (tasks
submitted under the same per-document lock, so they stay ordered) and read
concurrently by searches. Its maps are all concurrent — a sorted
`ConcurrentSkipListMap` for the postings (token → ids), `ConcurrentHashMap`s for the
rest — so reads and the background writer never corrupt each other; the only visible
effect is that a search may briefly miss a document that is mid-re-index (it's
skipped rather than erroring) — the eventual-consistency window noted above. A
stronger guarantee would build a document's postings off to the side and swap them
in atomically.

---

## Performance considerations

- **Search scales.** Indexing a document is a single linear tokenizing pass on
  write, run off the request thread so it never blocks the edit. Prefix matching
  over the sorted vocabulary is an `O(log V + matches)` range scan — the query
  jumps to the first token ≥ the prefix and stops as soon as one no longer starts
  with it — instead of scanning every document. The `PerformanceTest` searches a
  **10 MB** document well within its budget.
- **Editing works on segments, not characters.** `ChangeEngine.apply` locates an
  edit's range with a **binary search** over the segments' cumulative offsets
  (`O(log s)`) and splits only the segments it touches — it never expands the
  document to one object per character. Ranges resolve against the original text
  and overlaps are rejected before anything is applied.
- **Memory.** The engine allocates on the order of the number of *segments*, not
  the number of characters, so a redlined document stays cheap to edit (the
  benchmark edits a **1 MB** doc comfortably). Producing the new immutable segment
  list still copies the text once (`O(n)` characters); a rope with structural
  sharing would avoid even that copy for very large, frequently-edited documents.
- **Interactive editing feels instant.** Each keystroke is applied and rendered
  locally, so typing never waits on the network; edits are debounce-batched to the
  server (one request per pause, not one per keystroke) and indexing runs off the
  write path. See client-authoritative editing under *Design decisions*.
- **Trade-off, stated plainly.** The index costs roughly the corpus size again in
  memory and a re-index on each write, to make reads fast. That's the right trade
  when reads dominate writes — as they do for a search service.

### Scaling to very large documents (10 MB+)

Contracts are typically kilobytes to a few megabytes, so the current design keeps
those fast and simple. Three further optimizations matter only once documents reach
tens of megabytes, and are left as documented design rather than built:

- **Piece-table / rope engine (structural sharing).** Applying an edit today
  rebuilds the segment list and copies the untouched text once (`O(n)`). A rope or
  piece table represents the document as a tree of slices over shared buffers, so an
  edit splices a few nodes without copying — making `apply` `O(log s + edit)` in
  time and removing the copy entirely.
- **Windowed (virtualized) rendering.** The browser builds DOM for the whole
  document; for 10 MB that is millions of nodes. Rendering only the visible viewport
  (as CodeMirror/Monaco do) keeps the DOM bounded regardless of length, paired with
  `O(1)` caret math — mapping offsets within the rendered window instead of walking
  every text node.
- **Delta responses.** A write returns the whole document today. With edits batched
  this is already minor, but returning only the changed segments (a splice the client
  applies) would keep responses bounded for very large documents too.

---

## Testing

```bash
./gradlew test
```

- **Engine** — redline behavior (insert/delete/replace, edit vs. delete of your
  own insertion, layered edits), overlap/bounds rejection, and accept/reject
  (individual, partial, all, no-op).
- **Search index** — order-independence, title + text matching, prefix matching
  (and that an infix does *not* match), case-insensitivity, snippets (including
  original-casing and cache-refresh-on-re-index), removal, re-index.
- **API** (`MockMvc`) — every endpoint, including the sequential `/edits` stream
  (ordered application, single version bump, an edit atomic PATCH would reject),
  `{error, code}` bodies, and 404 / 412 / 422 paths.
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
| Bulk operations | atomic `changes: [...]` (PATCH) + ordered stream (`/edits`) |
| Performance / near-linear | inverted index; binary-search engine; off-thread indexing |
| Concurrency (versioning/ETag) | `version` + `If-Match` → 412 |
| In-memory inverted index + trade-offs | `SearchIndex`, above |
| API design (read/write split, REST vs. action) | above |

---

## Possible next steps

Per-change IDs (accept/reject without sending a span), search pagination
(`limit`/`offset`) and per-document search (`GET /documents/{id}/search`), the
large-document optimizations described above, persistence, auth, LLM-assisted
redline suggestions, and Operational Transform for real-time multi-user editing.
