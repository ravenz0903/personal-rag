const fs = require('node:fs/promises');
const path = require('node:path');
const { verifyAssets } = require('./assets.cjs');
const { ManagedRuntime, createBridge } = require('./managed-runtime.cjs');
const embedding = require('./embedding-lock.json');
const baseRevision = '1cfa9a7208912126459214e8b04321603b3df60c';

// Missing or damaged AI never prevents the ordinary workspace from starting.
async function startLocalAi(resources) {
  const root = path.join(resources, 'ai');
  try {
    const file = await fs.readFile(path.join(root, 'manifest.json'), 'utf8');
    if (file.length > 200000) throw Error('Oversized manifest');
    const manifest = JSON.parse(file), quality = manifest.qualification;
    if (manifest.baseRevision !== baseRevision || manifest.runtimeRevision !== 'b11429' ||
        quality?.exported !== true || quality.original !== 12 || quality.harder !== 16 ||
        !Number.isInteger(quality.qualification) || quality.qualification < 95 || quality.qualification > 100 ||
        quality.unsafe !== 0 || manifest.embeddingSpace !== embedding.embeddingSpace)
      throw Error('AI qualification is incomplete');
    const files = await verifyAssets(root, manifest);
    const asset = (name, role) => {
      const found = files.find(f => f.path === name && f.role === role);
      if (!found) throw Error('Required AI asset missing');
      return found.absolutePath;
    };
    const vector = files.find(f => f.role === 'embedding');
    if (vector.sha256 !== embedding.sha256 || vector.bytes !== embedding.bytes || vector.revision !== embedding.revision)
      throw Error('Embedding model differs from the qualified configuration');
    if (files.filter(f => f.role === 'chat').length !== 1 || files.filter(f => f.role === 'embedding').length !== 1)
      throw Error('Ambiguous model assets');
    const runtime = new ManagedRuntime({
      guard: asset('InferenceHost.exe', 'runtime'),
      cpu: asset('cpu/llama-server.exe', 'runtime'), gpu: asset('vulkan/llama-server.exe', 'runtime'),
      chat: files.find(f => f.role === 'chat').absolutePath, embedding: vector.absolutePath
    });
    const bridge = await createBridge(runtime);
    return { runtime, bridge, environment: { FOLIO_AI_PROVIDER: 'local', FOLIO_AI_ENABLED: 'true',
      FOLIO_LOCAL_AI_ORIGIN: bridge.origin, FOLIO_LOCAL_AI_TOKEN: bridge.key,
      FOLIO_LOCAL_AI_EMBEDDING_SPACE: embedding.embeddingSpace }, close: () => bridge.close() };
  } catch {
    // Do not expose paths, keys, or model content in renderer messages.
    console.warn('Local AI assets are absent or not qualified. The workspace will run without AI.');
    return { environment: {}, close: async () => {} };
  }
}
module.exports = { startLocalAi };
