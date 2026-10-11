// Real Windows process-tree cleanup, using a harmless sleeping child (no model).
const { spawn } = require('node:child_process');
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os');
const assert = require('node:assert/strict');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-inference-host-'));
  const pidFile = path.join(directory, 'child.pid');
  const hostPath = path.resolve(__dirname, '../../target/ai-native/InferenceHost.exe');
  const powershell = path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe');
  const command = `[System.IO.File]::WriteAllText('${pidFile.replaceAll("'", "''")}',$PID.ToString()); Start-Sleep -Seconds 60`;
  const host = spawn(hostPath, [powershell, '-NoProfile', '-NonInteractive', '-Command', command], { windowsHide: true, stdio: 'ignore', env: { ...process.env, FOLIO_INFERENCE_PARENT_PID: String(process.pid) } });
  let pid;
  const alive = () => { try { process.kill(pid, 0); return true; } catch { return false; } };
  try {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      if (host.exitCode !== null) throw Error('Inference host exited early: ' + host.exitCode);
      try { pid = Number(await fs.readFile(pidFile, 'utf8')); if (pid) break; } catch { /* child starting */ }
      await wait(100);
    }
    assert.ok(pid && alive(), 'The harmless child must really be running before termination');
    host.kill(); // Windows forcibly terminates the host; no JS/C# cleanup hook runs.
    for (let i = 0; i < 100 && alive(); i++) await wait(50);
    assert.equal(alive(), false, 'Job closure must kill the inference child after forced host termination');
    console.log('PASS: Windows job ownership terminates the real child when the inference host is forcibly stopped.');
  } finally {
    if (host.exitCode === null) host.kill();
    if (pid && alive()) { process.kill(pid); await wait(200); }
    await fs.rm(directory, { recursive: true, force: true });
  }
}
async function parentCrash() {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-owner-crash-'));
  const pidFile = path.join(directory, 'child.pid'), hostFile = path.join(directory, 'host.pid');
  const hostPath = path.resolve(__dirname, '../../target/ai-native/InferenceHost.exe');
  const powershell = path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe');
  const command = `[System.IO.File]::WriteAllText('${pidFile.replaceAll("'", "''")}',$PID.ToString()); Start-Sleep -Seconds 60`;
  const source = `const cp=require('node:child_process'),fs=require('node:fs');const p=cp.spawn(${JSON.stringify(hostPath)},${JSON.stringify([powershell, '-NoProfile', '-NonInteractive', '-Command', command])},{windowsHide:true,stdio:'ignore',env:{...process.env,FOLIO_INFERENCE_PARENT_PID:String(process.pid)}});fs.writeFileSync(${JSON.stringify(hostFile)},String(p.pid));setInterval(()=>{},1000);`;
  const parent = spawn(process.execPath, ['-e', source], { windowsHide: true, stdio: 'ignore' });
  const pids = [];
  const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
  try {
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      try { const child = Number(await fs.readFile(pidFile, 'utf8')), host = Number(await fs.readFile(hostFile, 'utf8')); if (child && host) { pids.push(child, host); break; } } catch {}
      await wait(100);
    }
    assert.equal(pids.length, 2); assert.ok(pids.every(alive));
    parent.kill();
    for (let i = 0; i < 100 && pids.some(alive); i++) await wait(50);
    assert.ok(pids.every(pid => !alive(pid)), 'Killing the app owner must also terminate its host and model');
    console.log('PASS: abrupt parent termination closes the inference host and its real child without callbacks.');
  } finally {
    if (parent.exitCode === null && parent.signalCode === null) parent.kill();
    for (const pid of pids) if (alive(pid)) process.kill(pid);
    await fs.rm(directory, { recursive: true, force: true });
  }
}
main().then(parentCrash).catch(error => { console.error(error); process.exitCode = 1; });
