
const base = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
let client;
export async function configure() {
  const response = await fetch(`${base}/api/config`);
  if (!response.ok) throw new Error('The workspace is waking up or unavailable. Please retry in a minute.');
  const config = await response.json();
  if (config.authEnabled) {
    if (!config.supabaseUrl || !config.supabasePublicKey) throw new Error('Login is not configured. Complete the backend setup first.');
    const { createClient } = await import('@supabase/supabase-js');
    client ||= createClient(config.supabaseUrl, config.supabasePublicKey);
  } else if (!['localhost', '127.0.0.1'].includes(location.hostname)) {
    throw new Error('This backend has authentication disabled. Use an authenticated cloud backend before publishing.');
  }
  return { config, client };
}
async function headers(json = true) {
  const result = json ? { 'Content-Type': 'application/json' } : {};
  if (client) {
    const { data, error } = await client.auth.getSession();
    if (error) throw error;
    if (!data.session) throw new Error('Sign in to continue.');
    result.Authorization = `Bearer ${data.session.access_token}`;
  }
  return result;
}
export async function api(path, options = {}) {
  const response = await fetch(`${base}${path}`, { ...options, headers: { ...await headers(!(options.body instanceof FormData)), ...options.headers } });
  if (!response.ok) {
    let message = response.status === 401 ? 'Your session expired or this account does not own the workspace. Sign in again.' : 'The request could not be completed.';
    try { const body = await response.json(); message = body.message || message; } catch { /* Empty HTTP error */ }
    throw new Error(message);
  }
  if (options.download) return response.blob();
  return response.json();
}
export const post = (path, body) => api(path, { method: 'POST', ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
export async function streamChat(query, domain, onEvent, signal, requestId) {
  const response = await fetch(`${base}/api/chat/stream?query=${encodeURIComponent(query)}&domain=${domain}${requestId ? '&requestId=' + encodeURIComponent(requestId) : ''}`, { headers: await headers(false), signal });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || 'Could not start the answer.');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder(); let buffer = '';
  function emit(block) {
    const lines = block.split('\n');
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim() || 'message';
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n');
    if (event === 'error') throw new Error(data || 'AI request failed.');
    if (event === 'citations') onEvent(event, JSON.parse(data));
    else onEvent(event, data);
  }
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, '\n');
      let boundary;
      while ((boundary = buffer.indexOf('\n\n')) !== -1) { emit(buffer.slice(0, boundary)); buffer = buffer.slice(boundary + 2); }
      if (done) { if (buffer.trim()) emit(buffer); break; }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
