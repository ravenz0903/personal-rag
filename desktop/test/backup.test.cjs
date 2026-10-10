const test = require('node:test'), assert = require('node:assert/strict');
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os'), { crc32 } = require('node:zlib'), crypto = require('node:crypto');
const { safeEntry, stageBackup, saveResponse, recoverInterruptedRestore } = require('../backup.cjs');
function archive(files) {
  const data = [], central = []; let offset = 0;
  for (const [name, contents] of files) {
    const filename = Buffer.from(name), bytes = Buffer.from(contents), crc = crc32(bytes), local = Buffer.alloc(30), dir = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt32LE(crc, 14); local.writeUInt32LE(bytes.length, 18); local.writeUInt32LE(bytes.length, 22); local.writeUInt16LE(filename.length, 26);
    dir.writeUInt32LE(0x02014b50, 0); dir.writeUInt16LE(20, 4); dir.writeUInt16LE(20, 6); dir.writeUInt32LE(crc, 16); dir.writeUInt32LE(bytes.length, 20); dir.writeUInt32LE(bytes.length, 24); dir.writeUInt16LE(filename.length, 28); dir.writeUInt32LE(offset, 42);
    data.push(local, filename, bytes); central.push(dir, filename); offset += local.length + filename.length + bytes.length;
  }
  const ending = Buffer.alloc(22), directory = Buffer.concat(central); ending.writeUInt32LE(0x06054b50, 0); ending.writeUInt16LE(files.length, 8); ending.writeUInt16LE(files.length, 10); ending.writeUInt32LE(directory.length, 12); ending.writeUInt32LE(offset, 16);
  return Buffer.concat([...data, directory, ending]);
}
async function fixture(fn) { const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-backup-test-')); try { await fn(directory); } finally { await fs.rm(directory, { recursive: true, force: true }); } }
test('reject traversal, alternate streams, executable and nested paths', () => { for (const name of ['../tracker.mv.db', '/data', 'C:/data', 'documents/../x', 'documents/a:b', 'main.cjs', 'documents\\x']) assert.throws(() => safeEntry(name)); assert.equal(safeEntry('tracker.mv.db', true), 'tracker.mv.db'); });
test('verified backup stages without touching the current workspace', () => fixture(async directory => {
  await fs.mkdir(path.join(directory, 'data')); await fs.writeFile(path.join(directory, 'data', 'original'), 'keep');
  const database = archive([['tracker.mv.db', Buffer.alloc(4096, 7)]]), doc = 'notes', docPath = 'documents/ACADEMICS/' + crypto.createHash('sha256').update('notes').digest('hex') + '/notes.md';
  const files = [['database.zip', database], [docPath, doc]], manifest = { formatVersion: 1, files: files.map(([name, data]) => ({ path: name, size: Buffer.byteLength(data), sha256: crypto.createHash('sha256').update(data).digest('hex') })) };
  const file = path.join(directory, 'backup.zip'); await fs.writeFile(file, archive([...files, ['manifest.json', JSON.stringify(manifest)]])); const staged = await stageBackup(file, directory);
  assert.equal((await fs.stat(path.join(staged.data, 'tracker.mv.db'))).size, 4096); assert.equal(await fs.readFile(path.join(directory, 'data', 'original'), 'utf8'), 'keep');
}));
test('reject modified manifests and clean only the staging directory', () => fixture(async directory => { const file = path.join(directory, 'bad.zip'); await fs.writeFile(file, archive([['database.zip', archive([['tracker.mv.db', Buffer.alloc(4096)]])], ['manifest.json', JSON.stringify({ formatVersion: 1, files: [{ path: 'database.zip', size: 1, sha256: 'wrong' }] })]])); await assert.rejects(stageBackup(file, directory), /verification/); assert.deepEqual(await fs.readdir(directory), ['bad.zip']); }));
test('failed downloads preserve a previously saved archive', () => fixture(async directory => { const file = path.join(directory, 'saved.zip'); await fs.writeFile(file, 'original'); await assert.rejects(saveResponse(new Response('error', { status: 500 }), file)); assert.equal(await fs.readFile(file, 'utf8'), 'original'); }));
test('interrupted replacement restores the previous folder before launch', () => fixture(async directory => { await fs.mkdir(path.join(directory,'data-before-restore-123'));await fs.writeFile(path.join(directory,'data-before-restore-123','old'),'saved');await fs.mkdir(path.join(directory,'data'));await fs.writeFile(path.join(directory,'data','new'),'unvalidated');await fs.writeFile(path.join(directory,'restore-journal.json'),JSON.stringify({prior:'data-before-restore-123'}));assert.equal(await recoverInterruptedRestore(directory),true);assert.equal(await fs.readFile(path.join(directory,'data','old'),'utf8'),'saved');assert.equal(await recoverInterruptedRestore(directory),false); }));
test('recovery refuses journal paths outside the workspace', () => fixture(async directory => {await fs.writeFile(path.join(directory,'restore-journal.json'),JSON.stringify({prior:'../another-folder'}));await assert.rejects(recoverInterruptedRestore(directory),/Invalid restore journal/);}));
