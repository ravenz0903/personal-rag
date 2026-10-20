const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { startBackend, waitForBackend } = require('../runtime.cjs');
const resources = process.argv[2] ? path.resolve(process.argv[2]) : path.resolve(__dirname, '../resources');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-smoke-'));
  let backend;
  try {
    backend = await startBackend(resources, directory);
    await waitForBackend(backend);
    const request = (route, options = {}) => fetch(backend.origin + route, { ...options, headers: { 'X-Folio-Desktop-Token': backend.token, 'Content-Type': 'application/json' } });
    assert.equal((await fetch(backend.origin + '/api/tracker/tasks?domain=ACADEMICS')).status, 401);
    const config = await (await request('/api/config')).json();
    assert.equal(config.desktopMode, true); assert.equal(config.aiEnabled, false); assert.equal(config.authEnabled, false);
    const page = await request('/'); assert.equal(page.status, 200); assert.match(await page.text(), /Folio/);
    const upload = await request('/api/ingest/markdown', { method: 'POST', body: JSON.stringify({ domain: 'ACADEMICS', path: 'offline.md', content: '# Local document\nNo AI calls.' }) });
    assert.equal(upload.status, 200); assert.equal((await upload.json()).chunksCreated, 0);
    const backup = await request('/api/desktop/backup'); assert.equal(backup.status, 200);
    assert.equal(Buffer.from(await backup.arrayBuffer()).subarray(0, 2).toString(), 'PK');
    assert.equal((await request('/api/assistant/actions', { method: 'POST', body: '{}' })).status, 503);
    console.log('Bundled Java runtime: startup, private API, frontend, offline upload, ZIP backup, and AI guard passed.');
  } finally {
    if (backend) {
      const exited = new Promise(resolve => { if (backend.child.exitCode !== null) resolve(); else backend.child.once('exit', resolve); });
      await fetch(backend.origin + '/api/desktop/shutdown', { method: 'POST', headers: { 'X-Folio-Desktop-Token': backend.token } });
      await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 15000))]);
      if (backend.child.exitCode === null) { backend.child.kill(); throw new Error('Graceful shutdown did not complete.'); }
    }
    // Keep the disposable workspace and log for inspection; no user records are touched.
    console.log('Smoke-test data:', directory);
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
