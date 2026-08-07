import { useEffect, useRef } from 'react';
import { applyChangeLocal, acceptedText, flatText, changeGroups } from '../redline.js';
import { escapeHtml } from '../highlight.js';
import { editStream, getDoc, acceptRange, rejectRange, acceptAll, rejectAll } from '../api.js';

const FLUSH_DELAY_MS = 400;    // debounce: coalesce a burst of typing into one save
const FLUSH_MAX_PENDING = 250; // ...but don't let the batch grow unbounded

// The interactive redline editor. React renders the static layout; a single
// mount effect wires up all editing imperatively (a contenteditable is easier to
// drive by hand than through React's virtual DOM). The redline transform is
// applied locally for instant feedback, then debounce-batched to the server.
export default function Editor({ initialDoc, onBack }) {
  const editorRef = useRef(null);
  const versionRef = useRef(null);
  const previewRef = useRef(null);
  const changeListRef = useRef(null);
  const acceptAllRef = useRef(null);
  const rejectAllRef = useRef(null);

  useEffect(() => {
    const editor = editorRef.current;
    const docId = initialDoc.id;
    let segments = initialDoc.segments || [];
    let version = initialDoc.version;
    let pending = [];            // keystrokes applied locally, not yet saved
    let saveChain = Promise.resolve();
    let flushTimer = null;
    let compositionStart = null;
    let alive = true;            // false after unmount; guards async callbacks

    // ---- rendering -------------------------------------------------------
    function renderEditor() {
      editor.innerHTML = segments
        .map((s) => {
          const cls = s.type === 'INSERTED' ? 'inserted' : s.type === 'DELETED' ? 'deleted' : '';
          return `<span class="${cls}">${escapeHtml(s.text)}</span>`;
        })
        .join('');
      versionRef.current.textContent = 'v' + version + (pending.length ? ' • saving…' : '');
      previewRef.current.textContent = 'If accepted: ' + acceptedText(segments);
      renderChanges();
    }
    function renderChanges() {
      const groups = changeGroups(segments);
      if (groups.length === 0) {
        changeListRef.current.innerHTML = '<div class="preview">No pending changes.</div>';
        return;
      }
      changeListRef.current.innerHTML = groups
        .map((g, i) => {
          const oldPart = g.oldText ? `<span class="old">${escapeHtml(g.oldText)}</span> ` : '';
          const newPart = g.newText
            ? `<span class="new">${escapeHtml(g.newText)}</span>`
            : '<span class="preview">(deletion)</span>';
          return `<div class="change"><div class="body">${i + 1}. ${oldPart}${newPart}</div>
            <div class="actions">
              <button class="btn primary" data-a="accept" data-start="${g.start}" data-end="${g.end}">Accept</button>
              <button class="btn" data-a="reject" data-start="${g.start}" data-end="${g.end}">Reject</button>
            </div></div>`;
        })
        .join('');
    }

    // ---- caret in flattened coordinates ----------------------------------
    function flattenedOffsetOf(container, offset) {
      const r = document.createRange();
      r.selectNodeContents(editor);
      r.setEnd(container, offset);
      return r.toString().length;
    }
    function caretOffsets() {
      const sel = window.getSelection();
      if (!sel.rangeCount) { const n = flatText(segments).length; return { start: n, end: n }; }
      const range = sel.getRangeAt(0);
      const start = flattenedOffsetOf(range.startContainer, range.startOffset);
      return { start, end: start + range.toString().length };
    }
    function setCaret(offset) {
      const clamped = Math.max(0, Math.min(offset, editor.textContent.length));
      const walker = document.createTreeWalker(editor, NodeFilter.SHOW_TEXT);
      let remaining = clamped, node;
      while ((node = walker.nextNode())) {
        if (remaining <= node.length) {
          const range = document.createRange();
          range.setStart(node, remaining);
          range.collapse(true);
          const sel = window.getSelection();
          sel.removeAllRanges();
          sel.addRange(range);
          return;
        }
        remaining -= node.length;
      }
      editor.focus();
    }

    // ---- authoritative reset ---------------------------------------------
    function applyDoc(doc) {
      clearTimeout(flushTimer);
      flushTimer = null;
      pending = [];
      segments = doc.segments || [];
      version = doc.version;
      renderEditor();
    }

    // ---- optimistic editing ----------------------------------------------
    function optimisticEdit(change, caret) {
      segments = applyChangeLocal(segments, change.range.start, change.range.end, change.replacement);
      pending.push(change);
      renderEditor();
      setCaret(caret);
      clearTimeout(flushTimer);
      flushTimer = setTimeout(flush, FLUSH_DELAY_MS);
      if (pending.length >= FLUSH_MAX_PENDING) flush();
    }
    function flush() {
      clearTimeout(flushTimer);
      flushTimer = null;
      if (pending.length === 0) return saveChain;
      saveChain = saveChain.then(async () => {
        if (pending.length === 0) return;
        const baseVersion = version, sending = pending;
        pending = [];
        try {
          const doc = await editStream(docId, sending, baseVersion);
          if (!alive) return;
          version = doc.version;
          if (pending.length === 0) {
            const focused = document.activeElement === editor;
            const caret = focused ? caretOffsets().start : 0;
            segments = doc.segments || [];
            renderEditor();
            if (focused) setCaret(caret);
          } else {
            // Still typing: refresh ONLY the version label; re-rendering here would
            // rebuild the editor DOM and jump the caret mid-keystroke.
            versionRef.current.textContent = 'v' + version + (pending.length ? ' • saving…' : '');
          }
        } catch (err) {
          if (!alive) return;
          alert(err.message);
          try { const d = await getDoc(docId); if (alive) applyDoc(d); } catch (_) { /* ignore */ }
        }
      });
      return saveChain;
    }
    async function runOp(fn) {
      // Push pending keystrokes first: accept/reject ranges are in the current
      // (optimistic) coordinate space, so the server must have those edits already.
      await flush();
      saveChain = saveChain.then(async () => {
        try {
          const d = await fn();
          if (alive) applyDoc(d);
        } catch (e) {
          if (!alive) return;
          alert(e.message);
          try { const d = await getDoc(docId); if (alive) applyDoc(d); } catch (_) { /* ignore */ }
        }
      });
      return saveChain;
    }

    // ---- input handling --------------------------------------------------
    function onBeforeInput(e) {
      if (e.isComposing || e.inputType === 'insertCompositionText') return;
      const { start, end } = caretOffsets();
      let range = null, replacement = '', caret = start;
      switch (e.inputType) {
        case 'insertText':
          replacement = e.data || ''; range = [start, end]; caret = start + replacement.length; break;
        case 'insertParagraph':
        case 'insertLineBreak':
          replacement = '\n'; range = [start, end]; caret = start + 1; break;
        case 'insertFromPaste':
          replacement = (e.dataTransfer && e.dataTransfer.getData('text')) || '';
          range = [start, end]; caret = start + replacement.length; break;
        case 'insertReplacementText': {
          const targets = e.getTargetRanges ? e.getTargetRanges() : [];
          if (targets.length) {
            const tr = targets[0];
            const s = flattenedOffsetOf(tr.startContainer, tr.startOffset);
            const en = flattenedOffsetOf(tr.endContainer, tr.endOffset);
            replacement = e.data || (e.dataTransfer && e.dataTransfer.getData('text')) || '';
            range = [s, en]; caret = s + replacement.length;
          }
          break;
        }
        case 'deleteContentBackward':
          if (start !== end) { range = [start, end]; caret = start; }
          else if (start > 0) { range = [start - 1, start]; caret = start - 1; } break;
        case 'deleteContentForward':
          if (start !== end) { range = [start, end]; caret = start; }
          else if (start < flatText(segments).length) { range = [start, start + 1]; caret = start; } break;
        default: break;
      }
      e.preventDefault();
      if (!range) return;
      optimisticEdit({ range: { start: range[0], end: range[1] }, replacement }, caret);
    }
    function onCompositionStart() { compositionStart = caretOffsets().start; }
    function onCompositionEnd(e) {
      const at = compositionStart != null ? compositionStart : caretOffsets().start;
      const data = e.data || '';
      compositionStart = null;
      if (!data) return;
      optimisticEdit({ range: { start: at, end: at }, replacement: data }, at + data.length);
    }
    function onBlur() { flush(); }
    function onChangeListClick(e) {
      const btn = e.target.closest('button[data-a]');
      if (!btn) return;
      const start = +btn.dataset.start, end = +btn.dataset.end;
      if (btn.dataset.a === 'accept') runOp(() => acceptRange(docId, start, end, version));
      else runOp(() => rejectRange(docId, start, end, version));
    }
    const onAcceptAll = () => runOp(() => acceptAll(docId, version));
    const onRejectAll = () => runOp(() => rejectAll(docId, version));

    // ---- wire up ---------------------------------------------------------
    editor.addEventListener('beforeinput', onBeforeInput);
    editor.addEventListener('compositionstart', onCompositionStart);
    editor.addEventListener('compositionend', onCompositionEnd);
    editor.addEventListener('blur', onBlur);
    const changeList = changeListRef.current;
    const acceptAllBtn = acceptAllRef.current;
    const rejectAllBtn = rejectAllRef.current;
    changeList.addEventListener('click', onChangeListClick);
    acceptAllBtn.addEventListener('click', onAcceptAll);
    rejectAllBtn.addEventListener('click', onRejectAll);

    renderEditor();
    editor.focus();

    return () => {
      alive = false;
      clearTimeout(flushTimer);
      editor.removeEventListener('beforeinput', onBeforeInput);
      editor.removeEventListener('compositionstart', onCompositionStart);
      editor.removeEventListener('compositionend', onCompositionEnd);
      editor.removeEventListener('blur', onBlur);
      changeList.removeEventListener('click', onChangeListClick);
      acceptAllBtn.removeEventListener('click', onAcceptAll);
      rejectAllBtn.removeEventListener('click', onRejectAll);
    };
  }, [initialDoc]);

  return (
    <div className="wrap" style={{ maxWidth: 1100 }}>
      <div className="editor-head">
        <button className="btn ghost" onClick={onBack}>← Library</button>
        <h1>{initialDoc.title || '(untitled)'}</h1>
        <span className="v" ref={versionRef}></span>
      </div>
      <div className="editor-grid">
        <div>
          <div id="editor" ref={editorRef} contentEditable spellCheck={false} suppressContentEditableWarning></div>
          <div className="preview" ref={previewRef}></div>
        </div>
        <div className="side">
          <h2>Pending changes</h2>
          <div className="allrow">
            <button className="btn" ref={acceptAllRef}>Accept all</button>
            <button className="btn" ref={rejectAllRef}>Reject all</button>
          </div>
          <div id="change-list" ref={changeListRef}></div>
        </div>
      </div>
    </div>
  );
}
