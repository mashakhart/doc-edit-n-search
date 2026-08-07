export default function Header({ view, onNav }) {
  return (
    <header>
      <div className="brand">Red<span className="dot">•</span>line</div>
      <nav>
        <button className={view === 'library' ? 'active' : ''} onClick={() => onNav('library')}>Library</button>
        <button className={view === 'new' ? 'active' : ''} onClick={() => onNav('new')}>New document</button>
      </nav>
    </header>
  );
}
