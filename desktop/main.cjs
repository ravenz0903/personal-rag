const { app, BrowserWindow, Tray, Menu, Notification, nativeImage, ipcMain, shell, dialog, session, powerMonitor } = require('electron');
const path = require('node:path');
const fs = require('node:fs/promises');
const { startBackend, waitForBackend } = require('./runtime.cjs');
const { stageBackup, saveResponse, recoverInterruptedRestore } = require('./backup.cjs');
const { startLocalAi } = require('./ai/service.cjs');
const { NoteDrafts } = require('./note-drafts.cjs');
const { createNotifier } = require('./notifications.cjs');

app.setAppUserModelId('com.folio.workspace');
app.setName('Folio');
// Honor Electron's explicit data-directory switch for isolated workspaces.
if (!app.commandLine.hasSwitch('user-data-dir')) app.setPath('userData', path.join(app.getPath('appData'), 'Folio'));
require('./logging.cjs').installLogger(app.getPath('userData'));
let window, tray, backend, localAi, quitting = false, restoring = false, polling = false, timer;
let noteDrafts;
let notify;
const notifications = new Set();
const resources = app.isPackaged ? process.resourcesPath : path.join(__dirname, 'resources');
const icon = () => nativeImage.createFromPath(path.join(__dirname, 'assets', 'folio.png'));
async function openBackend(data) {
  localAi = await startLocalAi(resources);
  try { return await startBackend(resources, data, localAi.environment); }
  catch (error) { await localAi.close(); throw error; }
}
function showWindow() { if (window && !window.isDestroyed()) { if (window.isMinimized()) window.restore(); window.show(); window.focus(); } }
async function request(url, options = {}) {
  const response = await fetch(backend.origin + url, { ...options,
    headers: { 'X-Folio-Desktop-Token': backend.token, 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error(`Workspace request failed (${response.status}).`);
  return response.json();
}
async function pollReminders() {
  if (polling || quitting || restoring || !backend) return;
  polling = true;
  try {
    const due = await request('/api/desktop/reminders/claim');
    for (const reminder of due) {
      if (restoring || quitting) break;
      try {
      const current = await request(`/api/desktop/reminders/eligible?id=${reminder.taskId}&kind=${reminder.kind}&at=${encodeURIComponent(reminder.remindAt)}`);
      if (!current.eligible) continue;
      await notify('You have a scheduled reminder. Open Folio to view it.', () => {
        if (window && !window.isDestroyed()) return window.loadURL(backend.origin + `/?record=${reminder.taskId}&kind=${reminder.kind}`);
      });
      if (!restoring && !quitting) await request('/api/desktop/reminders/delivered', { method: 'POST', body: JSON.stringify(reminder) });
      } catch(error) { await request('/api/desktop/reminders/failed', { method: 'POST', body: JSON.stringify(reminder) }).catch(() => {}); console.error('Reminder delivery:', error.message); }
    }
  } catch (error) { console.error('Reminder check:', error.message); }
  finally { polling = false; }
}
function trusted(event) {
  if (!window || event.sender !== window.webContents || event.senderFrame !== window.webContents.mainFrame ||
      new URL(event.senderFrame.url).origin !== backend.origin) throw new Error('Untrusted desktop request');
}
function registerActions() {
  noteDrafts=new NoteDrafts(app.getPath('userData'));
  ipcMain.on('folio:note-draft',(event,...args)=>{try{trusted(event);event.returnValue={value:noteDrafts.dispatch(...args)};}catch(error){event.returnValue={error:error.message};}});
  const handle = (channel, callback) => ipcMain.handle(channel, (event, ...args) => { trusted(event); return callback(...args); });
  handle('folio:settings', () => ({ runAtLogin: app.getLoginItemSettings().openAtLogin, notificationsSupported: Notification.isSupported(), dataPath: app.getPath('userData') }));
  handle('folio:run-at-login', enabled => {
    if (!app.isPackaged) throw new Error('Start with Windows becomes available after installing Folio.');
    app.setLoginItemSettings({ openAtLogin: enabled === true, args: ['--hidden'] });
    return { runAtLogin: app.getLoginItemSettings().openAtLogin };
  });
  handle('folio:test-notification', async () => { await notify('Your local reminders are ready. Folio stays in the tray when you close its window.'); return { ok: true }; });
  handle('folio:data-folder', async () => { const error = await shell.openPath(app.getPath('userData')); if (error) throw new Error(error); return { ok: true }; });
  handle('folio:backup', async () => {
    const chosen = await dialog.showSaveDialog(window, { title: 'Save workspace backup', defaultPath: `Folio-backup-${new Date().toISOString().slice(0, 10)}.zip`, filters: [{ name: 'Workspace backup', extensions: ['zip'] }] });
    if (chosen.canceled) return { canceled: true };
    const response = await fetch(backend.origin + '/api/desktop/backup', { headers: { 'X-Folio-Desktop-Token': backend.token }, signal: AbortSignal.timeout(120000) });
    if (!response.ok) throw new Error('Could not create backup. Your workspace has not been changed.');
    await saveResponse(response, chosen.filePath);
    return { saved: true };
  });
  handle('folio:export', async () => {
    const chosen = await dialog.showSaveDialog(window, { title: 'Export records and notes', defaultPath: 'Folio-export.zip', filters: [{ name: 'Workspace export', extensions: ['zip'] }] });
    if (chosen.canceled) return { canceled: true };
    await saveResponse(await fetch(backend.origin + '/api/local/export', { headers: { 'X-Folio-Desktop-Token': backend.token }, signal: AbortSignal.timeout(120000) }), chosen.filePath);
    return { saved: true };
  });
  handle('folio:restore', async () => {
    if (restoring) throw new Error('A restore is already in progress.');
    const chosen = await dialog.showOpenDialog(window, { title: 'Choose a Folio backup', properties: ['openFile'], filters: [{ name: 'Workspace backup', extensions: ['zip'] }] });
    if (chosen.canceled) return { canceled: true };
    const root = app.getPath('userData'), staged = await stageBackup(chosen.filePaths[0], root);
    const verified = p => { if (!path.resolve(p).startsWith(path.resolve(root) + path.sep)) throw new Error('Invalid restore directory.'); return p; };
    const prior = verified(path.join(root, 'data-before-restore-' + Date.now())), current = verified(path.join(root, 'data'));
    const journal = verified(path.join(root, 'restore-journal.json'));
    let swapped = false, draftArchive = null;
    try {
      const answer = await dialog.showMessageBox(window, { type: 'question', buttons: ['Cancel', 'Restore this backup'], defaultId: 0, cancelId: 0, title: 'Restore verified backup?', message: `Verified ${staged.files} files. Replace this workspace with the selected backup?`, detail: 'Folio will save a fresh backup and keep the previous data folder. The app restarts its workspace after restoration.' });
      if (answer.response !== 1) return { canceled: true };
      restoring = true; notify.clear();
      const deadline=Date.now()+15000;while(polling&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,100));if(polling)throw new Error('A reminder is still finishing. Retry restoration shortly.');
      const recovery = verified(path.join(root, 'restore-backups')); await fs.mkdir(recovery, { recursive: true });
      await saveResponse(await fetch(backend.origin + '/api/desktop/backup', { headers: { 'X-Folio-Desktop-Token': backend.token }, signal: AbortSignal.timeout(120000) }), path.join(recovery, `before-restore-${Date.now()}.zip`));
      await fs.writeFile(journal,JSON.stringify({prior:path.basename(prior)}),{flag:'wx'});
      await stopBackend(); await fs.rename(current, prior); swapped = true; await fs.rename(verified(staged.data), current);
      backend = await openBackend(root); watchBackend(); await waitForBackend(backend);
      draftArchive=noteDrafts.archiveForRestore();
      await window.webContents.executeJavaScript("Object.keys(localStorage).filter(k=>k.startsWith('folio-note-draft:')).forEach(k=>localStorage.removeItem(k))");
      await fs.rm(journal);await window.loadURL(backend.origin); return { restored: true };
    } catch (error) {
      if (swapped) {
        await stopBackend().catch(() => {});
        if (await fs.stat(current).catch(() => null)) await fs.rename(current, verified(path.join(root, 'data-failed-restore-' + Date.now())));
        if (draftArchive) noteDrafts.restoreArchive(draftArchive);
        await fs.rename(prior, current); backend = await openBackend(root); watchBackend(); await waitForBackend(backend); await window.loadURL(backend.origin);
      } else if (backend.child.exitCode !== null) {
        backend = await openBackend(root); watchBackend(); await waitForBackend(backend); await window.loadURL(backend.origin);
      }
      await fs.rm(journal,{force:true});
      throw new Error(`Restore stopped: ${error.message} The previous workspace is retained.`);
    } finally { restoring = false; await fs.rm(verified(staged.staging), { recursive: true, force: true }); pollReminders(); }
  });
}
function watchBackend() {
  backend.child.once('error', error => { if (!restoring) { dialog.showErrorBox('Folio could not start', error.message); app.quit(); } });
  backend.child.once('exit', () => { if (!quitting && !restoring) { dialog.showErrorBox('Workspace stopped', 'Restart Folio to reopen your workspace. Your records remain saved.'); app.quit(); } });
}
async function stopBackend() {
  await localAi?.close(); localAi = null;
  if (!backend || backend.child.exitCode !== null || backend.child.signalCode !== null) return;
  const child = backend.child, exited = new Promise(resolve => child.once('exit', resolve));
  await request('/api/desktop/shutdown', { method: 'POST' }).catch(() => {});
  await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 12000))]);
  if (child.exitCode === null && child.signalCode === null) { child.kill(); await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 5000))]); }
  if (child.exitCode === null && child.signalCode === null) throw new Error('The workspace could not be stopped safely.');
}
async function launch() {
  notify = createNotifier({ Notification, notifications, showWindow, icon, iconPath: path.join(__dirname, 'assets', 'folio.png'), report: (...args) => console.log(...args) });
  const data = app.getPath('userData');
  await fs.mkdir(data, { recursive: true });
  await recoverInterruptedRestore(data);
  session.defaultSession.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
  window = new BrowserWindow({ width: 1280, height: 850, minWidth: 740, minHeight: 560, title: 'Folio', backgroundColor: '#191919', icon: icon(), show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true } });
  window.setMenuBarVisibility(false);
  window.on('close', event => { if (!quitting) { event.preventDefault(); window.hide(); } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event, url) => { if (!backend || new URL(url).origin !== backend.origin) event.preventDefault(); });
  window.webContents.on('will-attach-webview', event => event.preventDefault());
  await window.loadFile(path.join(__dirname, 'assets', 'loading.html'));
  if (!process.argv.includes('--hidden')) showWindow();
  tray = new Tray(icon()); tray.setToolTip('Folio — local workspace');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Open Folio', click: showWindow },
    { label: 'Test reminder', click: () => { Promise.resolve().then(() => notify('Folio reminders are working.')).catch(e => dialog.showErrorBox('Notifications', e.message)); } },
    { type: 'separator' }, { label: 'Quit Folio', click: () => app.quit() }
  ]));
  tray.on('double-click', showWindow);
  backend = await openBackend(data);
  watchBackend();
  session.defaultSession.webRequest.onBeforeSendHeaders((details, callback) => {
    if (new URL(details.url).origin === backend.origin) details.requestHeaders['X-Folio-Desktop-Token'] = backend.token;
    callback({ requestHeaders: details.requestHeaders });
  });
  await waitForBackend(backend);
  registerActions();
  await window.loadURL(backend.origin);
  timer = setInterval(pollReminders, 20000); pollReminders();
  powerMonitor.on('resume', pollReminders);
}
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', showWindow);
  app.whenReady().then(launch).catch(error => { dialog.showErrorBox('Folio could not start', error.message); app.quit(); });
  app.on('before-quit', event => {
    if (restoring) {event.preventDefault();return;}
    if (quitting) return;
    quitting = true; clearInterval(timer);
    event.preventDefault();
    stopBackend().catch(error => console.error('Workspace shutdown:', error.message))
      .finally(() => { tray?.destroy(); app.exit(); });
  });
  app.on('window-all-closed', () => { /* The tray owns the running application. */ });
}
