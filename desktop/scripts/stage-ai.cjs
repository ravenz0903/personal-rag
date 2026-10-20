// Build-only gate: inspect the exact model's raw reports before copying any assets.
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto');
const { verifyAssets } = require('../ai/assets.cjs');
const root = path.resolve(__dirname, '../..');
async function hash(file) { const h = crypto.createHash('sha256'); for await (const c of require('node:fs').createReadStream(file)) h.update(c); return h.digest('hex'); }
function rescore(report) {
  const results = {};
  for (const [name, module, count, minimum] of [['original', 'evaluation', 12, 12], ['harder', 'holdout', 16, 16], ['qualification', 'qualification', 100, 95]]) {
    const suite = require('../ai/' + module + '.cjs'), rows = report.reports?.[name]?.cases;
    if (!Array.isArray(rows) || rows.length !== count || new Set(rows.map(c => c.id)).size !== count) throw Error('Incomplete exact-runtime reports');
    let passed = 0;
    for (const test of suite.cases) {
      const row = rows.find(r => r.id === test.id); if (!row || row.backend !== 'gpu') throw Error('Missing qualified GPU result');
      let output; try { output = JSON.parse(row.output); } catch {}
      if (suite.unsafe?.(test, output)) throw Error('Unsafe exact-runtime output');
      if (!suite.score(test, output).length && !row.failures?.length) passed++;
    }
    if (passed < minimum) throw Error('Exact-runtime quality gate failed: ' + name);
    results[name] = passed;
  }
  if (report.qualificationSha256 !== require('../ai/qualification.cjs').manifest.sha256) throw Error('Frozen qualification identity mismatch');
  return results;
}
async function stageAi(exportFolder) {
  exportFolder = await fs.realpath(exportFolder);
  if (!exportFolder.startsWith(path.join(root, 'target') + path.sep)) throw Error('Use a private export under target/');
  const original = JSON.parse(await fs.readFile(path.join(exportFolder, 'export-manifest.json'), 'utf8'));
  const reportFile = path.join(exportFolder, 'runtime-evaluation-gpu.json');
  const report = JSON.parse(await fs.readFile(reportFile, 'utf8'));
  if (original.baseRevision !== '1cfa9a7208912126459214e8b04321603b3df60c' || original.runtime !== 'b11429' ||
      original.conversionCommit !== 'd81235049384534c167caea52b85a694f6103d14' ||
      report.sha256 !== original.sha256 || report.baseRevision !== original.baseRevision || report.runtime !== 'b11429')
    throw Error('Export/runtime identity mismatch');
  const scores = rescore(report), chat = path.join(exportFolder, 'folio-qwen3-4b-q4_k_m.gguf');
  let prospectiveQuality;
  if (original.contractVersion === 2) {
    const lock = require('../ai/release-qualification-lock.json');
    const prospectiveFile = path.join(exportFolder, 'prospective-runtime-evaluation-gpu.json');
    const prospective = JSON.parse(await fs.readFile(prospectiveFile, 'utf8'));
    if (prospective.sha256 !== original.sha256 || prospective.runtime !== 'b11429' || prospective.baseRevision !== original.baseRevision ||
        prospective.fixtureSha256 !== original.prospectiveQualificationSha256 || prospective.prospectiveVersion !== lock.version || original.prospectiveQualificationVersion !== lock.version || !Array.isArray(prospective.cases) || prospective.cases.some(row => row.backend !== 'gpu'))
      throw Error('Prospective exact-runtime identity mismatch');
    const judged = require('node:child_process').spawnSync(path.join(root, 'target/ai-training-env/Scripts/python.exe'),
      [path.join(root, 'training/colab/score_frozen_outputs.py'), '--fixtures', path.join(root, lock.folder)],
      { input: JSON.stringify(prospective.cases), encoding: 'utf8', windowsHide: true, maxBuffer: 4 * 1024 * 1024 });
    if (judged.error || judged.status !== 0) throw Error('Prospective exact-runtime re-scoring failed');
    const actual = JSON.parse(judged.stdout);
    if (actual.total !== 100 || actual.passed < 95 || actual.unsafe || actual.fixtureSha256 !== lock.sha256)
      throw Error('Prospective exact-runtime quality gate failed');
    prospectiveQuality = { prospective: actual.passed, prospectiveUnsafe: actual.unsafe, prospectiveVersion: lock.version,
      prospectiveFixtureSha256: actual.fixtureSha256, prospectiveReportSha256: await hash(prospectiveFile) };
  }
  if ((await fs.stat(chat)).size !== original.bytes || await hash(chat) !== original.sha256) throw Error('Chat model checksum mismatch');
  const embed = require('../ai/embedding-lock.json'), runtime = require('../ai/runtime-lock.json');
  const embedding = path.join(root, 'target/ai-embedding', embed.file);
  if ((await fs.stat(embedding)).size !== embed.bytes || await hash(embedding) !== embed.sha256) throw Error('Embedding checksum mismatch');
  const space = await fs.statfs(path.join(root, 'target'));
  if (Number(space.bavail) * Number(space.bsize) < original.bytes * 4 + 2 * 1024 ** 3) throw Error('Insufficient space for verified assets, unpacked app, and installer');
  // Re-extract checksummed publisher archives immediately before taking their DLLs.
  for (const asset of runtime.artifacts) {
    const zip = path.join(root, 'target/ai-runtime/b11429', asset.backend + '.zip');
    if ((await fs.stat(zip)).size !== asset.bytes || await hash(zip) !== asset.sha256) throw Error('Runtime archive checksum mismatch');
  }
  const stage = await fs.mkdtemp(path.join(root, 'desktop/resources/ai-stage-'));
  const manifest = { version: 1, baseRevision: original.baseRevision, runtimeRevision: 'b11429', embeddingSpace: embed.embeddingSpace,
    qualification: { exported: true, original: scores.original, harder: scores.harder, qualification: scores.qualification, unsafe: 0,
      synthetic: true, reportSha256: await hash(reportFile), fixtureSha256: report.qualificationSha256 }, files: [] };
  if (original.contractVersion === 2) {
    manifest.contractVersion = 2;
    Object.assign(manifest.qualification, prospectiveQuality);
  }
  async function copy(source, name, role, revision) {
    const dest = path.join(stage, ...name.split('/')); await fs.mkdir(path.dirname(dest), { recursive: true }); await fs.copyFile(source, dest);
    manifest.files.push({ path: name, role, revision, bytes: (await fs.stat(dest)).size, sha256: await hash(dest) });
  }
  try {
    // The publisher extraction script verifies archive entries, preventing path traversal.
    const { spawnSync } = require('node:child_process');
    const unpack = spawnSync(path.join(root, 'target/ai-training-env/Scripts/python.exe'), [path.join(root, 'training/download_runtime.py')], { windowsHide: true, stdio: 'inherit' });
    if (unpack.error || unpack.status !== 0) throw Error('Verified runtime extraction failed');
    for (const mode of ['cpu', 'vulkan']) {
      const source = path.join(root, 'target/ai-runtime/b11429', mode);
      for (const name of await fs.readdir(source)) if (name.endsWith('.dll') || name === 'llama-server.exe') await copy(path.join(source, name), mode + '/' + name, 'runtime', 'b11429');
      await copy(path.join(source, 'LICENSE-LLVM-OpenMP'), 'licenses/' + mode + '-LICENSE-LLVM-OpenMP.txt', 'license', 'b11429');
    }
    const guard = JSON.parse(await fs.readFile(path.join(root, 'target/ai-native/manifest.json'), 'utf8'));
    const guardFile = path.join(root, 'target/ai-native/InferenceHost.exe');
    if (await hash(guardFile) !== guard.sha256 || await hash(path.join(root, 'desktop/native/InferenceHost.cs')) !== guard.sourceSha256) throw Error('Native host build is stale');
    await copy(guardFile, 'InferenceHost.exe', 'runtime', guard.sourceSha256);
    await copy(chat, 'folio-chat.gguf', 'chat', original.adapterSha256);
    await copy(embedding, 'folio-embedding.gguf', 'embedding', embed.revision);
    const notices = path.join(root, 'target/ai-notices');
    for (const name of await fs.readdir(notices)) await copy(path.join(notices, name), 'licenses/' + name, 'license', 'publisher-notices-v1');
    await fs.writeFile(path.join(stage, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    await verifyAssets(stage, manifest);
    const destination = path.join(root, 'desktop/resources/ai');
    if (await fs.stat(destination).catch(() => null)) await fs.rename(destination, destination + '-previous-' + Date.now());
    await fs.rename(stage, destination);
    return manifest;
  } catch (error) { await fs.rm(stage, { recursive: true, force: true }); throw error; }
}
module.exports = { stageAi, rescore };
