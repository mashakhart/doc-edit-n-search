export function escapeHtml(s) {
  return (s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Returns an HTML string with query tokens wrapped in <mark>. \b so we highlight
// where a WORD starts with the token, matching the server's prefix search
// (e.g. "appl" highlights the start of "apple").
export function highlight(text, tokens) {
  let html = escapeHtml(text);
  if (tokens && tokens.length) {
    const re = new RegExp('\\b(' + tokens.map(escapeRegex).join('|') + ')', 'gi');
    html = html.replace(re, '<mark class="hl">$1</mark>');
  }
  return html;
}
