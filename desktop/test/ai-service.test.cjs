const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os');
const { startLocalAi } = require('../ai/service.cjs');
test('missing or unqualified AI assets leave the core workspace enabled without AI', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-ai-service-'));
  try {
    let service = await startLocalAi(root);
    assert.deepEqual(service.environment, {}); await service.close();
    await fs.mkdir(path.join(root, 'ai'));
    await fs.writeFile(path.join(root, 'ai/manifest.json'), JSON.stringify({ version: 1,
      baseRevision: '1cfa9a7208912126459214e8b04321603b3df60c', runtimeRevision: 'b11429',
      qualification: { exported: false, original: 12, harder: 16, qualification: 100, unsafe: 0 } }));
    service = await startLocalAi(root);
    assert.deepEqual(service.environment, {}); assert.equal(service.bridge, undefined); await service.close();
  } finally { await fs.rm(root, { recursive: true, force: true }); }
});
