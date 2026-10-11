// Pinned build-time Java download. The installed app never downloads a runtime.
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto');
const { pipeline } = require('node:stream/promises');
const yauzl = require('yauzl');
const root = path.resolve(__dirname, '../..'), lock = require('../java-runtime-lock.json');
async function hash(file) { const h = crypto.createHash('sha256'); for await (const c of require('node:fs').createReadStream(file)) h.update(c); return h.digest('hex'); }
async function main() {
  const cache = path.join(root, 'target/java-runtime'); await fs.mkdir(cache, { recursive: true });
  const zip = path.join(cache, lock.archive);
  if (!await fs.stat(zip).catch(() => null)) {
    const response = await fetch(lock.url, { signal: AbortSignal.timeout(180000) });
    if (!response.ok) throw Error('Pinned Java runtime download failed');
    const partial = zip + '.partial'; await pipeline(response.body, require('node:fs').createWriteStream(partial)); await fs.rename(partial, zip);
  }
  if ((await fs.stat(zip)).size !== lock.bytes || await hash(zip) !== lock.sha256) throw Error('Java publisher checksum mismatch');
  const stage = await fs.mkdtemp(path.join(root, 'desktop/resources/runtime-stage-')); let top;
  try {
    await new Promise((resolve, reject) => yauzl.open(zip, { lazyEntries: true }, (error, archive) => {
      if (error) return reject(error);
      let count = 0, total = 0;
      archive.on('error', reject); archive.on('end', resolve);
      archive.on('entry', entry => {
        (async () => {
          const parts = entry.fileName.replace(/\/$/, '').split('/');
          if (++count > 2000 || parts.some(p => !p || p === '.' || p === '..' || p.includes(':') || p.includes('\\')) ||
              ((entry.externalFileAttributes >>> 16) & 0xf000) === 0xa000 || (top && parts[0] !== top)) throw Error('Unsafe Java archive');
          top ||= parts[0]; total += entry.uncompressedSize;
          if (total > 400 * 1024 ** 2) throw Error('Java runtime archive exceeds its limit');
          const file = path.join(stage, ...parts);
          if (entry.fileName.endsWith('/')) await fs.mkdir(file, { recursive: true });
          else {
            await fs.mkdir(path.dirname(file), { recursive: true });
            const stream = await new Promise((yes, no) => archive.openReadStream(entry, (err, value) => err ? no(err) : yes(value)));
            await pipeline(stream, require('node:fs').createWriteStream(file, { flags: 'wx' }));
          }
          archive.readEntry();
        })().catch(error => { archive.close(); reject(error); });
      });
      archive.readEntry();
    }));
    const extracted = path.join(stage, top), release = await fs.readFile(path.join(extracted, 'release'), 'utf8');
    if (!release.includes('IMPLEMENTOR="Eclipse Adoptium"') || !release.includes('JAVA_VERSION="21.')) throw Error('Unexpected Java runtime identity');
    const destination = path.join(root, 'desktop/resources/runtime');
    if (await fs.stat(destination).catch(() => null)) await fs.rename(destination, destination + '-previous-' + Date.now());
    await fs.rename(extracted, destination);
    await fs.writeFile(path.join(root, 'desktop/resources/java-runtime-manifest.json'), JSON.stringify({ ...lock, javaExecutableSha256: await hash(path.join(destination, 'bin/java.exe')) }, null, 2) + '\n');
    console.log('Verified Eclipse Temurin Java runtime is ready:', lock.release);
  } finally { await fs.rm(stage, { recursive: true, force: true }); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
