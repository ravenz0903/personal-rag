const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { crc32 } = require('node:zlib');
const { Transform, Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const yauzl = require('yauzl');
const LIMIT = 8 * 1024 ** 3;
function safeEntry(name, nested = false) {
  if (name.includes('\\') || name.includes(':') || name.startsWith('/') || name.split('/').some(p => p === '..' || p === '.' || !p)) throw new Error('Unsafe backup path.');
  if (nested ? name !== 'tracker.mv.db' : !['database.zip', 'manifest.json', 'RESTORE.txt'].includes(name) && !/^documents\/(ACADEMICS|CAREER|PERSONAL)\/[a-f0-9]{64}\/[a-zA-Z0-9._-]+$/.test(name)) throw new Error('Unexpected backup contents.');
  return name;
}
async function extract(file, directory, nested = false) {
  const zip = await new Promise((resolve, reject) => yauzl.open(file, { lazyEntries: true, validateEntrySizes: true }, (error, value) => error ? reject(error) : resolve(value)));
  const files = new Map(); let size = 0, count = 0;
  return new Promise((resolve, reject) => {
    const fail = error => { zip.close(); reject(error); };
    zip.on('error', fail); zip.on('end', () => resolve(files));
    zip.on('entry', async entry => {
      try {
        const name = safeEntry(entry.fileName, nested);
        if (++count > 10000 || (size += entry.uncompressedSize) > LIMIT || files.has(name) || ((entry.externalFileAttributes >>> 16) & 0o170000) === 0o120000 || entry.generalPurposeBitFlag & 1) throw new Error('Backup exceeds limits or contains unsafe entries.');
        const destination = path.resolve(directory, name); if (!destination.startsWith(path.resolve(directory) + path.sep)) throw new Error('Unsafe backup destination.');
        await fsp.mkdir(path.dirname(destination), { recursive: true });
        const input = await new Promise((r, j) => zip.openReadStream(entry, (e, stream) => e ? j(e) : r(stream)));
        const hash = crypto.createHash('sha256'); let checksum = 0, written = 0;
        const check = new Transform({ transform(chunk, _encoding, callback) { written += chunk.length; if (written > entry.uncompressedSize) return callback(new Error('Backup entry size changed.')); hash.update(chunk); checksum = crc32(chunk, checksum); callback(null, chunk); } });
        await pipeline(input, check, fs.createWriteStream(destination, { flags: 'wx' }));
        if (written !== entry.uncompressedSize || checksum !== entry.crc32) throw new Error('Backup checksum does not match.');
        files.set(name, { size: written, sha256: hash.digest('hex') }); zip.readEntry();
      } catch (e) { fail(e); }
    }); zip.readEntry();
  });
}
async function stageBackup(file, userData) {
  const root = path.resolve(userData), staging = await fsp.mkdtemp(path.join(root, 'restore-stage-'));
  if (!path.resolve(staging).startsWith(root + path.sep)) throw new Error('Unsafe staging directory.');
  try {
    const outer = path.join(staging, 'archive'), data = path.join(staging, 'data'); await fsp.mkdir(outer); await fsp.mkdir(data);
    const files = await extract(file, outer); if (!files.has('database.zip') || !files.has('manifest.json')) throw new Error('Choose a backup created by the current Folio version.');
    if (files.get('manifest.json').size > 4 * 1024 ** 2) throw new Error('Backup manifest is too large.');
    const manifest = JSON.parse(await fsp.readFile(path.join(outer, 'manifest.json'), 'utf8'));
    if (manifest.formatVersion !== 1 || !Array.isArray(manifest.files)) throw new Error('Unsupported backup version.');
    const listed = new Set();
    for (const expected of manifest.files) { const actual = files.get(expected.path); if (!actual || actual.size !== expected.size || actual.sha256 !== expected.sha256 || listed.has(expected.path) || expected.path.startsWith('documents/') && expected.path.split('/')[2] !== actual.sha256) throw new Error('Backup file verification failed.'); listed.add(expected.path); }
    for (const name of files.keys()) if (!['manifest.json','RESTORE.txt'].includes(name) && !listed.has(name)) throw new Error('Backup contains an unverified file.');
    const database = await extract(path.join(outer, 'database.zip'), data, true); if (!database.has('tracker.mv.db') || database.get('tracker.mv.db').size < 4096) throw new Error('Backup has no valid database.');
    if (fs.existsSync(path.join(outer, 'documents'))) await fsp.rename(path.join(outer, 'documents'), path.join(data, 'documents'));
    return { staging, data, files: manifest.files.length };
  } catch (e) { await fsp.rm(staging, { recursive: true, force: true }); throw e; }
}
async function saveResponse(response, destination) {
  if (!response.ok || !response.body) throw new Error('Could not create the workspace archive.');
  const temporary = destination + '.' + crypto.randomUUID() + '.partial';
  try { await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(temporary, { flags: 'wx' })); await fsp.rename(temporary, destination); }
  finally { await fsp.rm(temporary, { force: true }); }
}
async function recoverInterruptedRestore(userData) {
  const root = path.resolve(userData), journal = path.join(root, 'restore-journal.json');
  if (!fs.existsSync(journal)) return false;
  const record = JSON.parse(await fsp.readFile(journal,'utf8'));
  if (!/^data-before-restore-\d+$/.test(record.prior)) throw new Error('Invalid restore journal. Keep your data folders and use manual recovery.');
  const prior = path.join(root,record.prior), current = path.join(root,'data');
  if (fs.existsSync(prior)) { if (fs.existsSync(current)) await fsp.rename(current,path.join(root,'data-interrupted-restore-'+Date.now()));await fsp.rename(prior,current); }
  else if (!fs.existsSync(current)) throw new Error('Restore recovery could not find the previous workspace. Use the saved backup.');
  await fsp.rm(journal);return true;
}
module.exports = { safeEntry, stageBackup, saveResponse, recoverInterruptedRestore };
