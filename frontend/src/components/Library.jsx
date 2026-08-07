import { useEffect, useRef, useState } from 'react';
import { listDocs, searchDocs } from '../api.js';
import { highlight } from '../highlight.js';

export default function Library({ onOpen, onNew }) {
  const [docs, setDocs] = useState([]);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null); // null = show whole library
  const [tokens, setTokens] = useState([]);
  const [failed, setFailed] = useState(false);
  const timer = useRef(null);

  useEffect(() => {
    listDocs().then(setDocs).catch(() => {});
  }, []);

  const runSearch = async (q) => {
    if (!q) { setResults(null); setTokens([]); setFailed(false); return; }
    try {
      const r = (await searchDocs(q)).results;
      setResults(r);
      setTokens(q.toLowerCase().match(/\w+/g) || []);
      setFailed(false);
    } catch {
      setFailed(true);
    }
  };

  const onInput = (e) => {
    const q = e.target.value;
    setQuery(q);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => runSearch(q.trim()), 200);
  };

  // One card for both the library listing and search results; query tokens
  // highlight matches (empty tokens -> plain text).
  const Card = ({ id, title, text }) => (
    <div className="card" onClick={() => onOpen(id)}>
      <h3 dangerouslySetInnerHTML={{ __html: highlight(title || '(untitled)', tokens) }} />
      <div
        className="snippet"
        dangerouslySetInnerHTML={{ __html: highlight(text || '', tokens) || '<em>empty</em>' }}
      />
    </div>
  );

  let body;
  if (failed) {
    body = <div className="empty">Search failed.</div>;
  } else if (results === null) {
    body = docs.length === 0
      ? <div className="empty">No documents yet. Create one to get started.</div>
      : docs.map((d) => <Card key={d.id} id={d.id} title={d.title} text={d.preview} />);
  } else if (results.length === 0) {
    body = <div className="empty">No documents match your search.</div>;
  } else {
    body = results.map((r) => <Card key={r.docId} id={r.docId} title={r.title} text={r.snippet} />);
  }

  return (
    <div className="wrap">
      <div className="toolbar">
        <div><h1>Your documents</h1><p className="sub">Open one to redline it.</p></div>
        <button className="btn primary" onClick={onNew}>+ New document</button>
      </div>
      <input placeholder="Search documents…" value={query} onChange={onInput} style={{ marginBottom: 18 }} />
      <div className="grid">{body}</div>
    </div>
  );
}
