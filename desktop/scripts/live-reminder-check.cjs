// Requires the human to click real Windows notifications. No simulated click.
const {app,BrowserWindow,Notification,dialog}=require('electron');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const directory=fs.mkdtempSync(path.join(os.tmpdir(),'folio-live-reminder-'));
const setPath=app.setPath.bind(app);app.setPath=(name,value)=>setPath(name,name==='userData'?directory:value);
dialog.showErrorBox=(title,message)=>console.error(title,message);
let clicked=0;
const show=Notification.prototype.show;
Notification.prototype.show=function(){this.once('click',()=>{clicked++;console.log('REAL Windows notification click:',clicked);});return show.call(this);};
process.argv.push('--hidden');require('../main.cjs');
let window;
async function wait(check,label,timeout=100000){const end=Date.now()+timeout;while(Date.now()<end){if(await check())return;await new Promise(r=>setTimeout(r,250));}throw Error('Not verified: '+label);}
const js=code=>window.webContents.executeJavaScript(code,true);
async function request(url,body){return js(`fetch(${JSON.stringify(url)},{method:${JSON.stringify(body?'POST':'GET')},headers:{'Content-Type':'application/json'},${body?'body:'+JSON.stringify(JSON.stringify(body)):''}}).then(async r=>{if(!r.ok)throw Error(await r.text());return r.json()})`);}
async function run(){
  await wait(()=>{window=BrowserWindow.getAllWindows()[0];return window?.webContents.getURL().startsWith('http://');},'workspace startup');
  assert.equal(window.isVisible(),false);assert.equal(Notification.isSupported(),true);
  const preview=await request('/api/capture/structured',{domain:'ACADEMICS',entityType:'TASK',payload:{title:'Beta task reminder check',priority:'LOW',remindAt:new Date(Date.now()+5000).toISOString()}});
  await request('/api/capture/commit/'+preview.confirmationToken,{});
  const task=(await request('/api/local/tasks?domain=ACADEMICS')).find(t=>t.title==='Beta task reminder check');
  console.log('Please click the scheduled task notification.');
  await wait(()=>clicked>=1,'human task notification click',120000);
  await wait(()=>window.webContents.getURL().includes('record='+task.id)||js(`document.querySelector('.modal-card input[name=title]')?.value==='Beta task reminder check'`),'task editor');
  await wait(()=>js(`document.querySelector('.modal-card input[name=title]')?.value==='Beta task reminder check'`),'correct task title');
  assert.equal(window.isVisible(),true);console.log('PASS: actual task-notification click opens the correct task editor.');
  window.close();
  const event=await request('/api/local/calendar',{domain:'CAREER',title:'Beta event reminder check',allDay:false,startAt:new Date(Date.now()+3600000).toISOString(),endAt:new Date(Date.now()+5400000).toISOString(),remindAt:new Date(Date.now()+5000).toISOString()});
  console.log('Please click the scheduled event notification.');
  await wait(()=>clicked>=2,'human event notification click',120000);
  await wait(()=>js(`document.querySelector('.modal-card input[name=title]')?.value==='Beta event reminder check'`),'correct event title');
  assert.equal(window.isVisible(),true);assert.ok(event.id);console.log('PASS: actual event-notification click opens the correct event editor.');
  console.log('Actual sleep/wake remains a separate check. Disposable workspace:',directory);app.quit();
}
app.whenReady().then(run).catch(error=>{console.error(error);process.exitCode=1;app.quit();});
