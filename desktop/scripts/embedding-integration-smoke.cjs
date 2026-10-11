// Real private Java API + stored originals + pinned embeddings in disposable data.
// Chat intentionally unavailable to prove that core records survive model failure.
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os'), crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { ManagedRuntime, createBridge } = require('../ai/managed-runtime.cjs');
const { startBackend, waitForBackend } = require('../runtime.cjs');
const root = path.resolve(__dirname, '../..'), pin = require('../ai/embedding-lock.json');
async function main() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-real-index-'));
  const runtime = new ManagedRuntime({ guard: path.join(root, 'target/ai-native/InferenceHost.exe'),
    cpu: path.join(root, 'target/ai-runtime/b11429/cpu/llama-server.exe'),
    embedding: path.join(root, 'target/ai-embedding', pin.file) });
  const bridge = await createBridge(runtime); let backend;
  try {
    backend = await startBackend(path.join(root, 'desktop/resources'), directory, {
      FOLIO_AI_PROVIDER: 'local', FOLIO_AI_ENABLED: 'true', FOLIO_LOCAL_AI_ORIGIN: bridge.origin,
      FOLIO_LOCAL_AI_TOKEN: bridge.key, FOLIO_LOCAL_AI_EMBEDDING_SPACE: pin.embeddingSpace });
    await waitForBackend(backend);
    async function request(route, body) {
      const response = await fetch(backend.origin + route, { method: body ? 'POST' : 'GET',
        headers: { 'X-Folio-Desktop-Token': backend.token, ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}) });
      const data = await response.json(); assert.equal(response.status, 200, JSON.stringify(data)); return data;
    }
    const config = await request('/api/config'); assert.equal(config.aiProvider, 'local'); assert.equal(config.aiEnabled, true);
    assert.ok(!JSON.stringify(config).includes(bridge.key));
    assert.equal((await fetch(backend.origin + '/api/assistant/documents/status?domain=ALL')).status, 401);
    const content = '# Private note\nThe private telescope appointment is on 23 March 2040.';
    await request('/api/ingest/markdown', { path: 'private-note.md', content, domain: 'PERSONAL' });
    const secret = (await request('/api/ingest/documents?domain=PERSONAL'))[0];
    assert.equal(secret.chunkCount, 0);
    const indexed = await request('/api/assistant/documents/index', { documentId: secret.id, domain: 'PERSONAL', requestId: crypto.randomUUID() });
    assert.ok(indexed.chunks > 0);
    assert.equal((await request('/api/assistant/documents/status?domain=ALL')).length, 0);
    const hidden = await request('/api/chat/ask', { query: 'When is the private telescope appointment?', domain: 'ALL', requestId: crypto.randomUUID() });
    assert.equal(hidden.abstain, true); assert.deepEqual(hidden.citations, []); assert.ok(!hidden.answer.includes('23 March'));
    await request('/api/ingest/markdown', { path: 'exam.md', content: '# Exam\nThe thermodynamics exam is on 24 March 2040.', domain: 'ACADEMICS' });
    const doc = (await request('/api/ingest/documents?domain=ACADEMICS'))[0];
    await request('/api/assistant/documents/index', { documentId: doc.id, domain: 'ACADEMICS', requestId: crypto.randomUUID() });
    const download = await fetch(backend.origin + `/api/ingest/documents/${secret.id}/download?domain=PERSONAL`, { headers: { 'X-Folio-Desktop-Token': backend.token } });
    assert.equal(download.status, 200); assert.equal(await download.text(), content);
    const failure = await fetch(backend.origin + '/api/chat/ask', { method: 'POST', headers: { 'X-Folio-Desktop-Token': backend.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: 'When is the thermodynamics exam?', domain: 'ACADEMICS', requestId: crypto.randomUUID() }) });
    assert.equal(failure.status, 503, 'Missing chat must fail without crashing the backend');
    const draft = await request('/api/capture/structured', { domain: 'ACADEMICS', entityType: 'TASK', payload: { title: 'Core task after AI failure', priority: 'MEDIUM' } });
    await request('/api/capture/commit/' + draft.confirmationToken, {});
    assert.ok((await request('/api/local/tasks?domain=ALL')).some(t => t.title === 'Core task after AI failure'));
    const original = (await request('/api/ingest/documents?domain=PERSONAL'))[0];
    assert.equal(original.contentHash, secret.contentHash); assert.equal(original.chunkCount, secret.chunkCount);
    console.log('PASS: actual CPU indexing, private original download/checksum metadata, Personal exclusion, authenticated AI routes, and core writes after chat failure.');
    console.log('Disposable workspace:', directory);
  } finally {
    await bridge.close();
    if (backend) {
      const exit = new Promise(resolve => backend.child.exitCode !== null || backend.child.signalCode !== null ? resolve() : backend.child.once('exit', resolve));
      await fetch(backend.origin + '/api/desktop/shutdown', { method: 'POST', headers: { 'X-Folio-Desktop-Token': backend.token } }).catch(() => {});
      await Promise.race([exit, new Promise(resolve => setTimeout(resolve, 12000))]);
      if (backend.child.exitCode === null && backend.child.signalCode === null) backend.child.kill();
    }
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
