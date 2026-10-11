const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const path = require('node:path');
test('losing an inherited output pipe does not crash the Windows desktop process', async () => {
  const module = path.resolve(__dirname, '../logging.cjs');
  const child = spawn(process.execPath, ['-e', `require(${JSON.stringify(module)}).protectOutput();let n=0;const t=setInterval(()=>{console.log('progress');if(++n===20)clearInterval(t)},10)`], { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const exit = new Promise((resolve, reject) => { child.once('error', reject); child.once('exit', (code, signal) => resolve({ code, signal })); });
  child.stdout.destroy(); child.stderr.destroy();
  const result = await exit;
  assert.equal(result.code, 0); assert.equal(result.signal, null);
});
