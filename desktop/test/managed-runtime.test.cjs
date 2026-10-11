const test = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const { ManagedRuntime, createBridge, preferredDevice } = require('../ai/managed-runtime.cjs');
function fake(options = {}) {
  const children = [], calls = [];
  let port = 15000;
  const runtime = new ManagedRuntime({ guard: 'InferenceHost.exe', cpu: 'cpu/llama-server.exe', gpu: 'gpu/llama-server.exe', chat: 'chat.gguf', embedding: 'embedding.gguf' }, {
    freePort: async () => ++port, idleMs: 35, pollMs: 1, timeout: 500, loadTimeout: 50,
    selectDevice: async () => 'Vulkan1',
    spawn: (file, args, opts) => {
      const child = new EventEmitter(); child.exitCode = null; child.pid = 100 + children.length;
      child.kill = () => { child.exitCode = 0; queueMicrotask(() => child.emit('exit', 0)); return true; };
      children.push({ child, file, args, opts });
      if (options.gpuFails && args[0].startsWith('gpu')) { child.exitCode = 1; queueMicrotask(() => child.emit('exit', 1)); }
      return child;
    },
    fetch: async (url, init) => {
      calls.push({ url, init });
      if (url.endsWith('/health')) return { ok: true };
      if (options.perform) return options.perform(url, init);
      return { ok: true, json: async () => ({ result: 'ok' }) };
    }
  });
  return { runtime, children, calls };
}
test('runtime loads on demand, reuses chat, switches model, and unloads while idle', async () => {
  const { runtime, children, calls } = fake();
  assert.equal(children.length, 0);
  await runtime.request('chat', {}); await runtime.request('chat', {});
  assert.equal(children.length, 1);
  assert.equal(children[0].opts.env.LLAMA_API_KEY.length, 64);
  assert.equal(children[0].file, 'InferenceHost.exe');
  assert.equal(children[0].args[children[0].args.indexOf('--device') + 1], 'Vulkan1');
  assert.ok(children[0].args.includes('--no-agent'));
  assert.ok(children[0].args.includes('--offline'));
  assert.ok(calls.every(c => c.url.startsWith('http://127.0.0.1:') && c.init.headers.Authorization.startsWith('Bearer ')));
  await runtime.request('embedding', {});
  assert.equal(children[0].child.exitCode, 0);
  assert.ok(children[1].args.includes('--embedding'));
  assert.equal(children[1].args[children[1].args.indexOf('--ubatch-size') + 1], '4096');
  await new Promise(resolve => setTimeout(resolve, 60));
  assert.equal(children[1].child.exitCode, 0);
  assert.equal(runtime.status().state, 'idle');
  await runtime.close();
});
test('hybrid laptops prefer the dedicated GPU over a larger shared integrated budget', () => {
  assert.equal(preferredDevice('Vulkan0: AMD Radeon(TM) Graphics (8055 MiB, 7652 MiB free)\nVulkan1: NVIDIA GeForce RTX 4060 Laptop GPU (7956 MiB, 7188 MiB free)'), 'Vulkan1');
  assert.equal(preferredDevice('Vulkan0: AMD Radeon(TM) Graphics (8055 MiB, 7652 MiB free)'), 'Vulkan0');
  assert.throws(() => preferredDevice('No devices'));
});
test('Windows signal termination counts as stopped even without an exit code', async () => {
  const { runtime, children } = fake();
  await runtime.request('embedding', {});
  const child = children[0].child;
  child.kill = () => { child.signalCode = 'SIGTERM'; queueMicrotask(() => child.emit('exit', null, 'SIGTERM')); return true; };
  await runtime.close();
  assert.equal(child.exitCode, null);
  assert.equal(child.signalCode, 'SIGTERM');
});
test('failed GPU loading falls back to CPU, crash is recoverable, and Quit stops inference', async () => {
  const { runtime, children } = fake({ gpuFails: true });
  await runtime.request('chat', {});
  assert.equal(runtime.status().backend, 'cpu');
  children[1].child.exitCode = 1; children[1].child.emit('exit', 1);
  await runtime.request('chat', {});
  assert.equal(children.length, 4);
  await runtime.close();
  assert.ok(children.every(c => c.child.exitCode !== null));
  await assert.rejects(runtime.request('chat', {}), e => e.status === 503);
});
test('queue is bounded and cancellation aborts active inference and queued work', async () => {
  let started;
  const ready = new Promise(resolve => { started = resolve; });
  const { runtime } = fake({ perform: (_url, init) => new Promise((_resolve, reject) => {
    started(); init.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
  }) });
  const controller = new AbortController();
  const active = assert.rejects(runtime.request('chat', {}, controller.signal), e => e.status === 408);
  await ready;
  const queued = Array.from({ length: 4 }, () => assert.rejects(runtime.request('chat', {}), e => [408, 503].includes(e.status)));
  await assert.rejects(runtime.request('chat', {}), e => e.status === 429);
  controller.abort(); await active;
  await runtime.close(); await Promise.all(queued);
});
test('private bridge rejects browser origins and tool controls, never forwards secrets to status', async () => {
  const { runtime, calls } = fake(); const bridge = await createBridge(runtime);
  const headers = { Authorization: 'Bearer ' + bridge.key, 'Content-Type': 'application/json' };
  try {
    assert.equal((await fetch(bridge.origin + '/status')).status, 401);
    assert.equal((await fetch(bridge.origin + '/status', { headers: { ...headers, Origin: 'http://127.0.0.1' } })).status, 401);
    const bad = await fetch(bridge.origin + '/chat', { method: 'POST', headers, body: JSON.stringify({ messages: [{ role: 'user', content: 'hello' }], tools: ['shell'] }) });
    assert.equal(bad.status, 400); assert.equal(calls.length, 0);
    const good = await fetch(bridge.origin + '/chat', { method: 'POST', headers, body: JSON.stringify({ messages: [{ role: 'user', content: 'hello' }], json: true }) });
    assert.equal(good.status, 200);
    const forwarded = JSON.parse(calls.at(-1).init.body);
    assert.equal(forwarded.max_tokens, 512); assert.equal(forwarded.stream, false);
    assert.deepEqual(forwarded.response_format, { type: 'json_object' });
    const status = await (await fetch(bridge.origin + '/status', { headers })).text();
    assert.ok(!status.includes(bridge.key));
    assert.equal((await fetch(bridge.origin + '/embedding', { method: 'POST', headers, body: JSON.stringify({ input: 'x', model: 'external' }) })).status, 400);
  } finally { await bridge.close(); }
});
