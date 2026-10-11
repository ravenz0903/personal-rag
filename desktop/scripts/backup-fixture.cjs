// Disposable failure fixture: valid archive/checksums containing an unreadable database.
const { crc32 } = require('node:zlib');
const { createHash } = require('node:crypto');
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
function unreadableDatabaseBackup() {
  const database = archive([['tracker.mv.db', Buffer.alloc(8192, 7)]]);
  const manifest = { formatVersion: 1, files: [{ path: 'database.zip', size: database.length, sha256: createHash('sha256').update(database).digest('hex') }] };
  return archive([['database.zip', database], ['manifest.json', JSON.stringify(manifest)]]);
}
module.exports = { unreadableDatabaseBackup };
