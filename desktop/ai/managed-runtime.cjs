// Main-process inference owner. Never imported by the renderer.
const { spawn, execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { randomBytes } = require('node:crypto');
const net = require('node:net');
const http = require('node:http');
const { once } = require('node:events');

class RuntimeError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}
async function freePort() {
  const socket = net.createServer();
  await new Promise((resolve, reject) => { socket.once('error', reject); socket.listen(0, '127.0.0.1', resolve); });
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  return port;
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const exited = child => child.exitCode !== null || child.signalCode != null;
async function boundedWait(promise, ms) {
  let timer;
  try { await Promise.race([promise, new Promise(resolve => { timer = setTimeout(resolve, ms); })]); }
  finally { clearTimeout(timer); }
}
function cleanEnvironment() { return Object.fromEntries(Object.entries(process.env).filter(([name]) => !/^(LLAMA_|GGML_|HF_|HUGGING_|CUDA_VISIBLE_DEVICES|VK_)/i.test(name))); }
function preferredDevice(output) {
  const devices = [...output.matchAll(/^\s*(Vulkan\d+):\s*(.+?)\s*\((\d+) MiB,/gm)]
    .map(match => ({ id: match[1], name: match[2], memory: Number(match[3]) }));
  const rank = d => /NVIDIA|Radeon.*\bRX\b|Intel.*Arc/i.test(d.name) ? 1 : 0;
  devices.sort((a, b) => rank(b) - rank(a) || b.memory - a.memory);
  if (!devices.length) throw new RuntimeError(503, 'No supported GPU was detected.');
  return devices[0].id;
}
async function selectDevice(executable, signal) {
  const result = await promisify(execFile)(executable, ['--list-devices'], { windowsHide: true, timeout: 10000, signal, env: cleanEnvironment() });
  return preferredDevice(result.stdout + '\n' + result.stderr);
}
class ManagedRuntime {
  constructor(assets, options = {}) {
    this.assets = assets;
    this.spawn = options.spawn || spawn;
    this.fetch = options.fetch || fetch;
    this.freePort = options.freePort || freePort;
    this.selectDevice = options.selectDevice || selectDevice;
    this.timeout = options.timeout || 180000;
    this.loadTimeout = options.loadTimeout || 90000;
    this.idleMs = options.idleMs || 300000;
    this.pollMs = options.pollMs || 250;
    this.queue = []; this.active = null; this.running = null; this.closed = false;
    this.state = 'idle'; this.backend = null;
  }
  status() { return { available: !this.closed, state: this.state, backend: this.backend, queued: this.queue.length }; }
  request(kind, payload, signal) {
    if (this.closed) return Promise.reject(new RuntimeError(503, 'Local AI is stopped. Your records remain available.'));
    if (!['chat', 'embedding'].includes(kind)) return Promise.reject(new RuntimeError(400, 'Unsupported local AI operation.'));
    if (this.queue.length >= 4) return Promise.reject(new RuntimeError(429, 'Local AI is busy. Try again shortly.'));
    const controller = new AbortController();
    const combined = signal ? AbortSignal.any([controller.signal, signal]) : controller.signal;
    return new Promise((resolve, reject) => {
      const item = { kind, payload, signal: combined, controller, resolve, reject };
      item.timer = setTimeout(() => controller.abort(), this.timeout);
      item.abort = () => {
        const index = this.queue.indexOf(item);
        if (index >= 0) { this.queue.splice(index, 1); this.finish(item, new RuntimeError(408, 'Local AI request cancelled or timed out.')); }
      };
      combined.addEventListener('abort', item.abort, { once: true });
      this.queue.push(item);
      if (combined.aborted) item.abort();
      this.drain();
    });
  }
  finish(item, error, value) {
    clearTimeout(item.timer); item.signal.removeEventListener('abort', item.abort);
    if (error) item.reject(error); else item.resolve(value);
  }
  async drain() {
    if (this.active || this.closed || !this.queue.length) return;
    clearTimeout(this.idleTimer);
    const item = this.queue.shift(); this.active = item;
    try {
      await this.ensure(item.kind, item.signal);
      if (item.signal.aborted) throw new RuntimeError(408, 'Local AI request cancelled or timed out.');
      this.state = 'working';
      const response = await this.fetch(this.running.origin + (item.kind === 'chat' ? '/v1/chat/completions' : '/v1/embeddings'), {
        method: 'POST', headers: { Authorization: 'Bearer ' + this.running.key, 'Content-Type': 'application/json' },
        body: JSON.stringify(item.payload), signal: item.signal
      });
      if (!response.ok) throw new RuntimeError(response.status === 400 ? 400 : 503, 'Local AI could not process this request. Try a shorter request.');
      const data = await response.json();
      this.finish(item, null, data);
    } catch (error) {
      this.finish(item, error instanceof RuntimeError ? error : new RuntimeError(item.signal.aborted ? 408 : 503,
        item.signal.aborted ? 'Local AI request cancelled or timed out.' : 'Local AI is unavailable. Your records remain available.'));
    } finally {
      this.active = null;
      if (!this.closed) {
        this.state = this.running ? 'ready' : 'unavailable';
        if (this.queue.length) this.drain();
        else { this.idleTimer = setTimeout(() => this.unload().catch(() => {}), this.idleMs); this.idleTimer.unref?.(); }
      }
    }
  }
  async ensure(kind, signal) {
    if (this.running?.kind === kind && !exited(this.running.child) && !this.running.failed) return;
    await this.unload();
    for (const mode of this.assets.gpu ? ['gpu', 'cpu'] : ['cpu']) {
      if (signal.aborted || this.closed) throw new RuntimeError(408, 'Local AI loading cancelled.');
      this.state = 'loading'; this.backend = mode;
      const executable = this.assets[mode], model = this.assets[kind];
      if (!executable || !model || !this.assets.guard) throw new RuntimeError(503, 'Verified local AI assets are unavailable.');
      let device;
      if (mode === 'gpu') { try { device = await this.selectDevice(executable, signal); } catch { continue; } }
      const port = await this.freePort(), key = randomBytes(32).toString('hex');
      if (signal.aborted || this.closed) throw new RuntimeError(408, 'Local AI loading cancelled.');
      const args = ['--model', model, '--host', '127.0.0.1', '--port', String(port), '--ctx-size', '4096',
        '--parallel', '1', '--batch-size', kind === 'embedding' ? '4096' : '256', '--ubatch-size', kind === 'embedding' ? '4096' : '128', '--n-gpu-layers', mode === 'gpu' ? '99' : '0',
        '--threads', '4', '--offline', '--no-webui', '--no-agent', '--no-slots', '--log-verbosity', '1'];
      if (kind === 'embedding') args.push('--embedding', '--pooling', 'mean', '--rope-scaling', 'yarn', '--rope-freq-scale', '0.75');
      else args.push('--reasoning', 'off', '--chat-template-kwargs', '{"enable_thinking":false}');
      if (device) args.push('--device', device);
      const env = cleanEnvironment();
      const child = this.spawn(this.assets.guard, [executable, ...args], { windowsHide: true, stdio: 'ignore', env: { ...env, LLAMA_API_KEY: key, FOLIO_INFERENCE_PARENT_PID: String(process.pid) } });
      const running = { child, key, kind, origin: `http://127.0.0.1:${port}`, failed: false };
      this.running = running;
      child.once('error', () => { running.failed = true; });
      child.once('exit', () => { running.failed = true; if (this.running === running) { this.state = 'unavailable'; this.backend = null; } });
      try {
        const deadline = Date.now() + this.loadTimeout;
        while (Date.now() < deadline) {
          if (signal.aborted || this.closed) throw new Error('cancelled');
          if (running.failed || exited(child)) throw new Error('load failed');
          try {
            const health = await this.fetch(running.origin + '/health', {
              headers: { Authorization: 'Bearer ' + key }, signal: AbortSignal.any([signal, AbortSignal.timeout(1500)])
            });
            if (health.ok) return;
          } catch { /* A loading model may not yet accept health requests. */ }
          await pause(this.pollMs);
        }
        throw new Error('load timed out');
      } catch {
        await this.unload();
        if (mode === 'cpu' || signal.aborted || this.closed) throw new RuntimeError(503, 'Local AI could not load. Your records remain available.');
      }
    }
  }
  async unload() {
    clearTimeout(this.idleTimer);
    const running = this.running; this.running = null; this.backend = null;
    if (!running) return;
    const child = running.child;
    if (!exited(child) && child.pid) {
      const exit = once(child, 'exit').catch(() => {});
      child.kill();
      await boundedWait(exit, 5000);
      if (!exited(child)) { child.kill('SIGKILL'); await boundedWait(exit, 2000); }
      if (!exited(child)) throw new RuntimeError(503, 'Local AI could not stop safely.');
    }
    if (!this.closed && !this.active) this.state = 'idle';
  }
  async close() {
    this.closed = true; this.state = 'stopped'; clearTimeout(this.idleTimer);
    for (const item of this.queue.splice(0)) this.finish(item, new RuntimeError(503, 'Local AI stopped.'));
    this.active?.controller.abort();
    await this.unload();
  }
}

// Only the backend receives this bridge's secret. No renderer-facing control endpoint.
async function createBridge(runtime) {
  const key = randomBytes(32).toString('hex');
  const server = http.createServer(async (req, res) => {
    const respond = (status, body) => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)); } };
    if (req.headers.authorization !== 'Bearer ' + key || req.headers.origin || !['127.0.0.1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress)) return respond(401, { error: 'Unauthorized' });
    if (req.method === 'GET' && req.url === '/status') return respond(200, runtime.status());
    if (req.method !== 'POST' || !['/chat', '/embedding'].includes(req.url)) return respond(404, { error: 'Not found' });
    const abort = new AbortController();
    res.once('close', () => { if (!res.writableEnded) abort.abort(); });
    try {
      let size = 0, chunks = [];
      for await (const chunk of req) {
        size += chunk.length;
        if (size > 65536) throw new RuntimeError(413, 'Local AI request is too large.');
        chunks.push(chunk);
      }
      let payload;
      try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new RuntimeError(400, 'Invalid local AI request.'); }
      const kind = req.url.slice(1);
      // Allow only bounded inference fields; never relay tools, URL images, file paths, or arbitrary server controls.
      if (kind === 'chat') {
        if (!Array.isArray(payload.messages) || payload.messages.length < 1 || payload.messages.length > 8 ||
          payload.messages.some(m => !['system', 'user'].includes(m.role) || typeof m.content !== 'string' || m.content.length > 16000) ||
          Object.keys(payload).some(k => !['messages', 'json'].includes(k)) || (payload.json !== undefined && typeof payload.json !== 'boolean'))
          throw new RuntimeError(400, 'Invalid local AI messages.');
        payload = { messages: payload.messages, temperature: 0, max_tokens: 512, stream: false, chat_template_kwargs: { enable_thinking: false },
          ...(payload.json ? { response_format: { type: 'json_object' } } : {}) };
      } else {
        if (typeof payload.input !== 'string' || !payload.input.trim() || payload.input.length > 12000 || Object.keys(payload).some(k => k !== 'input'))
          throw new RuntimeError(400, 'Invalid embedding input.');
        payload = { input: payload.input, encoding_format: 'float' };
      }
      respond(200, await runtime.request(kind, payload, abort.signal));
    } catch (error) { respond(error.status || 503, { error: error.status ? error.message : 'Local AI is unavailable.' }); }
  });
  server.requestTimeout = 200000; server.headersTimeout = 10000;
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return { origin: `http://127.0.0.1:${server.address().port}`, key,
    close: async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await runtime.close(); } };
}
module.exports = { ManagedRuntime, createBridge, RuntimeError, preferredDevice };
