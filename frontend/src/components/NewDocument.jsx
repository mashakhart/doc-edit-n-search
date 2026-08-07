import { useState } from 'react';
import { createDoc } from '../api.js';

export default function NewDocument({ onCreated, onCancel }) {
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');

  const save = async () => {
    try {
      const doc = await createDoc(text, title.trim() || null);
      onCreated(doc);
    } catch (e) {
      alert(e.message);
    }
  };

  return (
    <div className="wrap" style={{ maxWidth: 640 }}>
      <h1>New document</h1>
      <p className="sub">Give it a title and paste the text you want to redline.</p>
      <label>Title</label>
      <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Master Services Agreement" />
      <label>Text</label>
      <textarea rows={12} value={text} onChange={(e) => setText(e.target.value)} placeholder="Paste document text here…" />
      <div style={{ marginTop: 18, display: 'flex', gap: 8 }}>
        <button className="btn primary" onClick={save}>Save</button>
        <button className="btn ghost" onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}
