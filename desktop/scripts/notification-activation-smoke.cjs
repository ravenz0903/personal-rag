// Real launcher/backend/renderer; Windows activation events are injected here.
const { app, BrowserWindow, powerMonitor } = require('electron');
const fs = require('node:fs'), os = require('node:os'), path = require('node:path');
const assert = require('node:assert/strict'), { EventEmitter } = require('node:events');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'folio-notification-qa-'));
const originalSetPath = app.setPath.bind(app);
app.setPath = (name, value) => originalSetPath(name, name === 'userData' ? directory : value);
app.setPath('userData', directory);
process.argv.push('--hidden');
const notifier = require('../notifications.cjs'), originalFactory = notifier.createNotifier;
const held = [];
class Notification extends EventEmitter {
  static isSupported() { return true; }
  static handleActivation(callback) { this.activate = callback; }
  constructor(options) { super(); this.options = options; held.push(this); }
  show() { this.emit('show'); }
  close() { this.emit('close', { reason: 'applicationHidden' }); }
}
notifier.createNotifier = options => originalFactory({ ...options, Notification });
const runtime = require('../runtime.cjs'), originalStart = runtime.startBackend;
let backend;
runtime.startBackend = async (...args) => { backend = await originalStart(...args); return backend; };
const output = { ...console };
require('../main.cjs');
Object.assign(console, output);
require('../logging.cjs').protectOutput();
async function wait(check, label, timeout = 95000) {
  const until = Date.now() + timeout;
  while (Date.now() < until) { if (await check()) return; await new Promise(resolve => setTimeout(resolve, 100)); }
  throw Error('Timed out: ' + label);
}
async function api(route, body) {
  const response = await fetch(backend.origin + route, { method: body ? 'POST' : 'GET', headers: { 'X-Folio-Desktop-Token': backend.token, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
  const result = await response.json();
  if (!response.ok) throw Error(route + ' ' + response.status + ' ' + JSON.stringify(result));
  return result;
}
async function run() {
  let window;
  await wait(() => { window = BrowserWindow.getAllWindows()[0]; return window?.webContents.getURL().startsWith('http://'); }, 'launcher ready');
  assert.equal(app.getPath('userData'), directory, 'Never write QA records outside the disposable workspace');
  const preview = await api('/api/capture/structured', { domain: 'PERSONAL', entityType: 'TASK', payload: { title: 'Notification target fixture', priority: 'LOW', remindAt: new Date(Date.now() + 1500).toISOString(), recurrence: 'NONE' } });
  const saved = await api('/api/capture/commit/' + preview.confirmationToken, {});
  await new Promise(resolve => setTimeout(resolve, 1800));
  powerMonitor.emit('resume');
  await wait(() => held.length === 1, 'wake reminder', 10000);
  const notification = held[0];
  window.hide();
  notification.emit('close', { reason: 'timedOut' });
  Notification.activate({ type: 'click', arguments: 'type=click&tag=' + notification.options.id });
  await wait(() => window.isVisible(), 'hidden Folio reopened');
  await wait(async () => { try { return await window.webContents.executeJavaScript("document.querySelector('.modal-card input[name=title]')?.value === 'Notification target fixture'"); } catch { return false; } }, 'correct task editor');
  assert.equal((await api('/api/local/open/task/' + saved.entityId)).domain, 'PERSONAL');
  let navigations = 0;
  window.webContents.on('did-start-navigation', () => navigations++);
  notification.emit('click');
  await new Promise(resolve => setTimeout(resolve, 200));
  assert.equal(navigations, 0, 'Duplicate instance click must not reload task editor');
  powerMonitor.emit('resume');
  await new Promise(resolve => setTimeout(resolve, 500));
  assert.equal(held.length, 1, 'Delivered wake reminder must not be emitted again');
  console.log('PASS: real launcher wake polling, timed-out Windows activation, hidden window reopening, correct Personal task editor, no duplicate activation/navigation or delivery.');
  console.log('Windows event is injected; physical clicking remains a human check.');
  console.log('Disposable workspace:', directory);
  app.quit();
}
run().catch(error => { console.error(error); process.exitCode = 1; app.quit(); });
