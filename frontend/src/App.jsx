import { useState } from 'react';
import Header from './components/Header.jsx';
import Library from './components/Library.jsx';
import NewDocument from './components/NewDocument.jsx';
import Editor from './components/Editor.jsx';
import { getDoc } from './api.js';

export default function App() {
  const [view, setView] = useState('library');
  const [doc, setDoc] = useState(null);

  const openDoc = async (id) => {
    try {
      const d = await getDoc(id);
      setDoc(d);
      setView('editor');
    } catch (e) {
      alert(e.message);
    }
  };

  return (
    <>
      <Header view={view} onNav={setView} />
      <main>
        {view === 'library' && <Library onOpen={openDoc} onNew={() => setView('new')} />}
        {view === 'new' && (
          <NewDocument
            onCreated={(d) => { setDoc(d); setView('editor'); }}
            onCancel={() => setView('library')}
          />
        )}
        {view === 'editor' && doc && (
          <Editor key={doc.id} initialDoc={doc} onBack={() => setView('library')} />
        )}
      </main>
    </>
  );
}
