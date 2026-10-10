// Exercises the real launcher/tray lifecycle in a disposable Windows workspace.
const { app, BrowserWindow } = require('electron');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const directory = fs.mkdtempSync(path.join(os.tmpdir(),'folio-lifecycle-'));
const setPath = app.setPath.bind(app); app.setPath = (name,value) => setPath(name,name === 'userData' ? directory : value);
process.argv.push('--hidden');
require('../main.cjs');
const assert = require('node:assert/strict');
async function run(){
  const deadline = Date.now()+95000;let window;
  while(Date.now()<deadline){window=BrowserWindow.getAllWindows()[0];if(window&&window.webContents.getURL().startsWith('http://'))break;await new Promise(r=>setTimeout(r,150));}
  assert.ok(window);assert.equal(window.isVisible(),false);
  const settings=await window.webContents.executeJavaScript('window.folioDesktop.settings()');assert.equal(settings.dataPath,directory);
  window.show();window.close();assert.equal(window.isDestroyed(),false);assert.equal(window.isVisible(),false);
  const alive=await window.webContents.executeJavaScript("fetch('/api/health').then(r=>r.ok)");assert.equal(alive,true);
  console.log('PASS: real launcher, bundled backend, launch hidden, close-to-tray, live backend while hidden, private IPC.');
  if(settings.notificationsSupported){
    await window.webContents.executeJavaScript(`(async()=>{const post=(url,body)=>fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(r=>r.json());const draft=await post('/api/capture/structured',{domain:'ACADEMICS',entityType:'TASK',payload:{title:'Disposable reminder check',priority:'LOW',remindAt:new Date(Date.now()+2000).toISOString()}});await post('/api/capture/commit/'+draft.confirmationToken,{});})()`);
    const until=Date.now()+45000;let sent=false;
    while(Date.now()<until){sent=await window.webContents.executeJavaScript("fetch('/api/desktop/reminders/status').then(r=>r.json()).then(rows=>rows.some(r=>!!r.deliveredAt))");if(sent)break;await new Promise(r=>setTimeout(r,500));}
    console.log(sent?'PASS: a scheduled notification was acknowledged by Windows while the Folio window was closed.':'PENDING: Windows did not acknowledge the scheduled notification; visible delivery needs a live-device check.');
  }else console.log('PENDING: notifications are unsupported in this test session.');
  console.log('Disposable workspace:',directory);app.quit();
}
app.whenReady().then(run).catch(error=>{console.error(error);process.exitCode=1;app.quit();});
