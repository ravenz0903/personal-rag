// Actual pinned embedding inference; ordinary mode uses CPU while GPU training runs.
const path = require('node:path');
const assert = require('node:assert/strict');
const { ManagedRuntime, createBridge } = require('../ai/managed-runtime.cjs');
const root = path.resolve(__dirname, '../..');
const runtime = new ManagedRuntime({
  guard: path.join(root, 'target/ai-native/InferenceHost.exe'),
  cpu: path.join(root, 'target/ai-runtime/b11429/cpu/llama-server.exe'),
  ...(process.argv.includes('--gpu') ? { gpu: path.join(root, 'target/ai-runtime/b11429/vulkan/llama-server.exe') } : {}),
  embedding: path.join(root, 'target/ai-embedding/nomic-embed-text-v1.5.Q8_0.gguf')
});
async function main() {
  const bridge = await createBridge(runtime);
  const headers = { Authorization: 'Bearer ' + bridge.key, 'Content-Type': 'application/json' };
  try {
    assert.equal((await fetch(bridge.origin + '/status')).status, 401);
    const vectors = [];
    for (const input of [
      'search_query: When is the thermodynamics exam?',
      'search_document: The thermodynamics exam is scheduled for Friday. Revise heat transfer and entropy.',
      'search_document: Bake the chocolate cake at 180 degrees and serve with whipped cream.'
    ]) {
      const response = await fetch(bridge.origin + '/embedding', { method: 'POST', headers, body: JSON.stringify({ input }) });
      const data = await response.json();
      assert.equal(response.status, 200, JSON.stringify(data));
      const vector = data.data?.[0]?.embedding;
      assert.equal(vector?.length, 768);
      assert.ok(vector.every(Number.isFinite));
      const length = Math.hypot(...vector);
      assert.ok(length > 0.001);
      vectors.push(vector.map(v => v / length));
    }
    const dot = (a, b) => a.reduce((sum, v, i) => sum + v * b[i], 0);
    const related = dot(vectors[0], vectors[1]), unrelated = dot(vectors[0], vectors[2]);
    assert.ok(related > unrelated + 0.05, 'The related passage must rank above the unrelated passage');
    const backend = runtime.status().backend;
    const host = runtime.running.child;
    await bridge.close();
    assert.ok(host.exitCode !== null || host.signalCode !== null, 'Model host must really stop');
    console.log(JSON.stringify({ passed: true, dimensions: 768, backend, related, unrelated, closed: runtime.status().state }));
  } finally { await bridge.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
