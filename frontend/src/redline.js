// A client-side mirror of ONE range-replace from the server's ChangeEngine, so a
// keystroke renders instantly (correctly coloured) without waiting for the server.
// It must match the Java engine's single-change behaviour; the server stays
// authoritative and re-syncs on each flush. Out-of-range is clamped (the caret
// can't produce it) rather than thrown — the server does the real validation.
export function applyChangeLocal(segments, start, end, replacement) {
  const offs = [0];
  for (const s of segments) offs.push(offs[offs.length - 1] + s.text.length);
  const total = offs[offs.length - 1];
  start = Math.max(0, Math.min(start, total));
  end = Math.max(start, Math.min(end, total));

  const emit = (from, to, retype) => {
    const out = [];
    if (from >= to) return out;
    let pos = from, i = 0;
    while (i < segments.length && offs[i + 1] <= from) i++;
    while (pos < to && i < segments.length) {
      const segStart = offs[i], segEnd = offs[i + 1];
      const pieceStart = Math.max(pos, segStart), pieceEnd = Math.min(to, segEnd);
      if (pieceEnd > pieceStart) {
        const newType = retype(segments[i].type);
        if (newType !== null) {
          out.push({ text: segments[i].text.slice(pieceStart - segStart, pieceEnd - segStart), type: newType });
        }
      }
      pos = pieceEnd; i++;
    }
    return out;
  };

  const ident = (t) => t;
  const strike = (t) => (t === 'INSERTED' ? null : 'DELETED'); // deleting your own insertion drops it
  let res = emit(0, start, ident).concat(emit(start, end, strike));
  if (replacement) res.push({ text: replacement, type: 'INSERTED' });
  res = res.concat(emit(end, total, ident));

  const out = []; // coalesce adjacent same-type runs
  for (const s of res) {
    const last = out[out.length - 1];
    if (last && last.type === s.type) out[out.length - 1] = { text: last.text + s.text, type: s.type };
    else out.push(s);
  }
  return out;
}

/** Flattened text the user sees (struck characters included). */
export const flatText = (segments) => segments.map((s) => s.text).join('');

/** The document if all changes were accepted (everything except deletions). */
export const acceptedText = (segments) =>
  segments.filter((s) => s.type !== 'DELETED').map((s) => s.text).join('');

/**
 * Groups consecutive non-UNCHANGED segments into one accept/reject unit. Adjacent
 * redlines merge into a single "change" (an insertion touching a deletion becomes
 * one) because there are no per-change IDs yet.
 */
export function changeGroups(segments) {
  const groups = [];
  let offset = 0, cur = null;
  for (const seg of segments) {
    if (seg.type !== 'UNCHANGED') {
      if (!cur) cur = { start: offset, end: offset, oldText: '', newText: '' };
      cur.end = offset + seg.text.length;
      if (seg.type === 'DELETED') cur.oldText += seg.text;
      else cur.newText += seg.text;
    } else if (cur) {
      groups.push(cur);
      cur = null;
    }
    offset += seg.text.length;
  }
  if (cur) groups.push(cur);
  return groups;
}
