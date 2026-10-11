const fs = require('node:fs'), path = require('node:path'), util = require('node:util');
function protectOutput() {
  for (const stream of [process.stdout, process.stderr]) stream.on('error', error => {
    // A detached Windows GUI can lose its inherited terminal. This is not an app crash.
    if (!['EPIPE', 'ERR_STREAM_DESTROYED'].includes(error.code)) throw error;
  });
}
function installLogger(folder) {
  protectOutput();
  fs.mkdirSync(folder, { recursive: true });
  const file = path.join(folder, 'desktop.log');
  for (const level of ['log', 'warn', 'error']) console[level] = (...values) => {
    try {
      if (fs.existsSync(file) && fs.statSync(file).size > 5 * 1024 * 1024) {
        fs.rmSync(file + '.previous', { force: true }); fs.renameSync(file, file + '.previous');
      }
      fs.appendFileSync(file, new Date().toISOString() + ' ' + level + ' ' + util.format(...values).slice(0, 8000) + '\n');
    } catch { /* A log-write failure must not crash the workspace or interrupt a restore. */ }
  };
}
module.exports = { protectOutput, installLogger };
