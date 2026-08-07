// Thin fetch wrapper. Throws Error(message) on non-2xx using the server's
// { error, code } body; sends If-Match for optimistic concurrency when given.
async function api(method, path, body, ifMatch) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (ifMatch != null) headers['If-Match'] = '"' + ifMatch + '"';
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return null;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((data && data.error) || `${method} ${path} failed`);
  return data;
}

export const listDocs = () => api('GET', '/documents');
export const createDoc = (text, title) => api('POST', '/documents', { text, title });
export const getDoc = (id) => api('GET', '/documents/' + id);
export const editStream = (id, changes, v) => api('POST', '/documents/' + id + '/edits', { changes }, v);
export const acceptRange = (id, start, end, v) => api('POST', '/documents/' + id + '/accept', { start, end }, v);
export const rejectRange = (id, start, end, v) => api('POST', '/documents/' + id + '/reject', { start, end }, v);
export const acceptAll = (id, v) => api('POST', '/documents/' + id + '/accept-all', undefined, v);
export const rejectAll = (id, v) => api('POST', '/documents/' + id + '/reject-all', undefined, v);
export const searchDocs = (q) => api('GET', '/documents/search?q=' + encodeURIComponent(q));
