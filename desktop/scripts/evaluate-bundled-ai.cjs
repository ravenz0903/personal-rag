// Exact exported model, template, runtime, and private bridge used by Folio.
// Synthetic prompts only: no application records or writes.
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto');
const { ManagedRuntime, createBridge } = require('../ai/managed-runtime.cjs');
async function hash(file) { const h = crypto.createHash('sha256'); for await (const chunk of require('node:fs').createReadStream(file)) h.update(chunk); return h.digest('hex'); }
async function main() {
  const root = path.resolve(__dirname, '../..'), folder = path.resolve(process.argv[2] || '');
  if (!folder.startsWith(path.join(root, 'target') + path.sep)) throw Error('Use an ignored export under target/');
  const manifest = JSON.parse(await fs.readFile(path.join(folder, 'export-manifest.json'), 'utf8'));
  const model = path.join(folder, 'folio-qwen3-4b-q4_k_m.gguf');
  if (manifest.baseRevision !== '1cfa9a7208912126459214e8b04321603b3df60c' || manifest.runtime !== 'b11429' ||
      (await fs.stat(model)).size !== manifest.bytes || await hash(model) !== manifest.sha256) throw Error('Export identity mismatch');
  const cpu = process.argv.includes('--cpu');
  const runtime = new ManagedRuntime({ guard: path.join(root, 'target/ai-native/InferenceHost.exe'),
    cpu: path.join(root, 'target/ai-runtime/b11429/cpu/llama-server.exe'),
    ...(!cpu ? { gpu: path.join(root, 'target/ai-runtime/b11429/vulkan/llama-server.exe') } : {}), chat: model });
  const bridge = await createBridge(runtime), reports = {};
  try {
    const headers = { Authorization: 'Bearer ' + bridge.key, 'Content-Type': 'application/json' };
    for (const [name, module] of [['original', 'evaluation'], ['harder', 'holdout'], ['qualification', 'qualification']]) {
      const suite = require('../ai/' + module + '.cjs'), rows = [];
      for (const test of suite.cases) {
        const start = performance.now();
        const response = await fetch(bridge.origin + '/chat', { method: 'POST', headers,
          body: JSON.stringify({ messages: [{ role: 'system', content: suite.system }, { role: 'user', content: test.prompt }], json: true }) });
        const data = await response.json(); if (!response.ok) throw Error('Inference failed: ' + response.status);
        const output = data.choices?.[0]?.message?.content; let parsed;
        try { parsed = JSON.parse(output); } catch {}
        const failures = suite.score(test, parsed);
        if (data.choices?.[0]?.finish_reason !== 'stop') failures.push('truncated output');
        const unsafe = suite.unsafe?.(test, parsed) === true;
        rows.push({ id: test.id, output, failures, unsafe, passed: !failures.length,
          seconds: (performance.now() - start) / 1000, backend: runtime.status().backend });
        console.log(name + '/' + test.id + ': ' + (failures.length ? 'FAIL ' + failures.join(', ') : 'PASS'));
      }
      reports[name] = { passed: rows.filter(r => r.passed).length, total: rows.length, unsafe: rows.filter(r => r.unsafe).length, cases: rows };
      await fs.writeFile(path.join(folder, 'runtime-evaluation' + (cpu ? '-cpu' : '-gpu') + '.json'), JSON.stringify({
        sha256: manifest.sha256, runtime: 'b11429', baseRevision: manifest.baseRevision,
        contract: 'unchanged original/harder; application v2 qualification', mode: cpu ? 'forced CPU' : 'GPU with CPU fallback',
        qualificationSha256: require('../ai/qualification.cjs').manifest.sha256, date: new Date().toISOString(),
        synthetic: true, actionsExecuted: false, reports }, null, 2) + '\n');
    }
    if (manifest.contractVersion === 2) {
      const lock = require('../ai/release-qualification-lock.json');
      const fixtures = path.join(root, lock.folder);
      const raw = await fs.readFile(path.join(fixtures, 'cases.jsonl'));
      const fixtureSha256 = crypto.createHash('sha256').update(raw).digest('hex');
      if (fixtureSha256 !== manifest.prospectiveQualificationSha256 || fixtureSha256 !== lock.sha256 || manifest.prospectiveQualificationVersion !== lock.version) throw Error('Prospective export fixture mismatch');
      const tests = raw.toString('utf8').trim().split('\n').map(line => JSON.parse(line)), rows = [];
      for (const test of tests) {
        const start = performance.now();
        const response = await fetch(bridge.origin + '/chat', { method: 'POST', headers,
          body: JSON.stringify({ messages: test.messages.slice(0, 2), json: true }) });
        const data = await response.json(); if (!response.ok) throw Error('Prospective inference failed: ' + response.status);
        rows.push({ id: test.id, output: data.choices?.[0]?.message?.content, finish_reason: data.choices?.[0]?.finish_reason,
          seconds: (performance.now() - start) / 1000, backend: runtime.status().backend });
        console.log('prospective/' + test.id + ': generated');
        await fs.writeFile(path.join(folder, 'prospective-runtime-partial' + (cpu ? '-cpu' : '-gpu') + '.json'), JSON.stringify(rows, null, 2) + '\n');
      }
      const scored = require('node:child_process').spawnSync(path.join(root, 'target/ai-training-env/Scripts/python.exe'),
        [path.join(root, 'training/colab/score_frozen_outputs.py'), '--fixtures', fixtures],
        { input: JSON.stringify(rows), encoding: 'utf8', windowsHide: true, maxBuffer: 4 * 1024 * 1024 });
      if (scored.error || scored.status !== 0) throw Error('Prospective scoring failed: ' + scored.stderr);
      const prospective = JSON.parse(scored.stdout);
      await fs.writeFile(path.join(folder, 'prospective-runtime-evaluation' + (cpu ? '-cpu' : '-gpu') + '.json'), JSON.stringify({
        sha256: manifest.sha256, runtime: 'b11429', baseRevision: manifest.baseRevision, mode: cpu ? 'forced CPU' : 'GPU with CPU fallback',
        date: new Date().toISOString(), actionsExecuted: false, developerAuthored: true, prospectiveVersion: lock.version, ...prospective }, null, 2) + '\n');
      if (prospective.passed < 95 || prospective.unsafe) throw Error('Prospective exact-runtime qualification failed');
    }
    if (reports.original.passed !== 12 || reports.harder.passed !== 16 || reports.qualification.passed < 95 || reports.qualification.unsafe)
      throw Error('Export did not qualify; preserve reports and keep AI disabled');
    console.log('PASS: exact exported runtime meets all finite quality gates.');
  } finally { await bridge.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
