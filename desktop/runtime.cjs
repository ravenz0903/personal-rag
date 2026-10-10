const path = require('node:path');
const fs = require('node:fs');
const { randomBytes } = require('node:crypto');
const { spawn } = require('node:child_process');
const net = require('node:net');

async function availablePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
function paths(resources, userData) {
  return { java: path.join(resources, 'runtime', 'bin', 'java.exe'), jar: path.join(resources, 'backend.jar'),
    frontend: path.join(resources, 'frontend'), data: path.join(userData, 'data') };
}
function backendArgs(files, port) {
  return ['-Xms64m', '-Xmx384m', '-jar', files.jar, '--spring.profiles.active=desktop', `--server.port=${port}`,
    `--spring.web.resources.static-locations=${new URL('file:///' + files.frontend.replaceAll('\\', '/') + '/').href}`];
}
function startBackend(resources, userData) {
  return availablePort().then(port => {
    const files = paths(resources, userData);
    for (const required of [files.java, files.jar, path.join(files.frontend, 'index.html')])
      if (!fs.existsSync(required)) throw new Error('Desktop resources are missing. Run npm run prepare in desktop first.');
    fs.mkdirSync(files.data, { recursive: true });
    const token = randomBytes(32).toString('hex');
    const environment = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(SPRING_|SUPABASE_|DATABASE_|GEMINI_|AI_|TRACKER_|REMINDERS_|VAPID_|FRONTEND_|FOLIO_|PORT$)/i.test(key)));
    const child = spawn(files.java, backendArgs(files, port), {
      cwd: userData, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'],
      env: { ...environment, FOLIO_DESKTOP_TOKEN: token, FOLIO_PARENT_PID: String(process.pid), GEMINI_API_KEY: '',
        SPRING_PROFILES_ACTIVE: 'desktop', FOLIO_DATA_DIR: files.data.replaceAll('\\', '/') }
    });
    const log = fs.createWriteStream(path.join(userData, 'backend.log'), { flags: 'w' });
    child.stdout.pipe(log, { end: false }); child.stderr.pipe(log, { end: false });
    child.once('close', () => log.end());
    return { child, token, origin: `http://127.0.0.1:${port}`, files };
  });
}
async function waitForBackend(backend, timeout = 90000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (backend.child.exitCode !== null) throw new Error('The workspace could not start. See backend.log in the data folder.');
    try {
      const response = await fetch(backend.origin + '/api/health', {
        headers: { 'X-Folio-Desktop-Token': backend.token }, signal: AbortSignal.timeout(1500)
      });
      if (response.ok) return;
    } catch { /* Startup is still in progress. */ }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error('The workspace took too long to start. See backend.log in the data folder.');
}
module.exports = { paths, backendArgs, startBackend, waitForBackend };
