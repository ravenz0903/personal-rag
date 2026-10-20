// Real launcher/restore checks in a disposable workspace. Native dialog choices
// are supplied by this harness; visible toast and actual sleep remain device gates.
const {app,BrowserWindow,dialog}=require('electron');
const fs=require('node:fs'),fsp=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const {spawn}=require('node:child_process');
const assert=require('node:assert/strict');
const directory=process.env.FOLIO_SMOKE_ROOT||fs.mkdtempSync(path.join(os.tmpdir(),'folio-stability-'));
const originalSetPath=app.setPath.bind(app);
app.setPath=(name,value)=>originalSetPath(name,name==='userData'?directory:value);
let finished=false;
const children=[],runtime=require('../runtime.cjs'),start=runtime.startBackend;
runtime.startBackend=async(...args)=>{const result=await start(...args);children.push(result);result.child.once('exit',()=>{if(finished){for(const backend of children)assert.notEqual(backend.child.exitCode,null);console.log('PASS: explicit Quit stopped every backend process.');}});return result;};
let chosen,confirmation=0,confirmations=0,saveCanceled=false,failExport=false;
const originalFetch=global.fetch;global.fetch=(url,options)=>failExport&&String(url).endsWith('/api/local/export')?Promise.resolve(new Response('Injected export failure',{status:500})):originalFetch(url,options);
dialog.showSaveDialog=async()=>({canceled:saveCanceled,filePath:chosen});
dialog.showOpenDialog=async()=>({canceled:false,filePaths:[chosen]});
dialog.showMessageBox=async(_window,options)=>{assert.equal(options.defaultId,0);confirmations++;return {response:confirmation};};
dialog.showErrorBox=(title,message)=>console.error('Native error:',title,message);
process.argv.push('--hidden');
const testConsole = { log: console.log, warn: console.warn, error: console.error };
require('../main.cjs');
Object.assign(console, testConsole); // Test reports use the harness-owned output file.
require('../logging.cjs').protectOutput();
async function wait(check,label,timeout=95000){const end=Date.now()+timeout;while(Date.now()<end){if(await check())return;await new Promise(r=>setTimeout(r,150));}throw Error('Timed out: '+label);}
let window;
const js=code=>window.webContents.executeJavaScript(code,true);
async function request(route,body,method=body?'POST':'GET'){
  const backend=children.at(-1),response=await fetch(backend.origin+route,{method,headers:{'X-Folio-Desktop-Token':backend.token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
  if(!response.ok)throw Error(await response.text());return response.json();
}
async function note(id){return request(`/api/local/notes/${id}?domain=ACADEMICS`);}
async function run(){
  await wait(()=>{window=BrowserWindow.getAllWindows()[0];return window?.webContents.getURL().startsWith('http://');},'cold startup');
  assert.equal(window.isVisible(),false);
  for(let i=0;i<4;i++){window.show();window.close();assert.equal(window.isDestroyed(),false);assert.equal(window.isVisible(),false);assert.equal((await request('/api/health')).status,'ok');}
  const second=spawn(process.execPath,[__filename,'--second-instance'],{windowsHide:true,env:{...process.env,FOLIO_SMOKE_ROOT:directory},stdio:'ignore'});
  await new Promise((resolve,reject)=>{second.once('error',reject);second.once('exit',code=>code===0?resolve():reject(Error('Second instance failed '+code)));});
  await wait(()=>window.isVisible(),'single instance reopens existing window',10000);
  assert.equal(children.length,1);window.close();
  console.log('PASS: cold startup, repeated close/reopen, tray survival, and real second-instance lock.');
  const original=await request('/api/local/notes',{domain:'ACADEMICS',title:'Stability original',tags:[],content:{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'Original backup text'}]}]}});
  const privateExportNote=await request('/api/local/notes',{domain:'PERSONAL',title:'Private export fixture',tags:[],content:{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'Private fixture body'}]}]}});
  await request('/api/ingest/markdown',{domain:'ACADEMICS',path:'export-fixture.md',content:'# Export fixture original'});
  chosen=path.join(directory,'workspace-export.zip');saveCanceled=true;assert.equal((await js('window.folioDesktop.export()')).canceled,true);assert.equal(fs.existsSync(chosen),false);saveCanceled=false;assert.equal((await js('window.folioDesktop.export()')).saved,true);
  const exportEntries=await new Promise((resolve,reject)=>require('yauzl').open(chosen,{lazyEntries:true},(error,zip)=>{if(error)return reject(error);const entries=new Map();zip.on('error',reject);zip.on('end',()=>resolve(entries));zip.on('entry',entry=>zip.openReadStream(entry,(error,stream)=>{if(error)return reject(error);const chunks=[];stream.on('error',reject);stream.on('data',chunk=>chunks.push(chunk));stream.on('end',()=>{entries.set(entry.fileName,Buffer.concat(chunks));zip.readEntry();});}));zip.readEntry();}));
  const exportedRecords=JSON.parse(exportEntries.get('records.json'));assert.equal(exportedRecords.formatVersion,1);assert.ok(exportedRecords.local_notes.some(row=>row.id===original.id));assert.ok(exportedRecords.local_notes.some(row=>row.id===privateExportNote.id&&row.domain==='PERSONAL'));assert.match(exportEntries.get('notes/'+original.id+'.md').toString(),/Original backup text/);assert.match(exportEntries.get('notes/'+privateExportNote.id+'.md').toString(),/Private fixture body/);assert.equal(JSON.parse(exportEntries.get('files-manifest.json')).length,1);
  const savedExport=fs.readFileSync(chosen);failExport=true;await assert.rejects(js('window.folioDesktop.export()'));assert.deepEqual(fs.readFileSync(chosen),savedExport);failExport=false;assert.equal((await js('window.folioDesktop.export()')).saved,true);
  console.log('PASS: native export cancellation, record IDs/domains, Markdown notes and original-file manifest; failed export preserves the previous archive and retry succeeds.');
  chosen=path.join(directory,'verified.zip');
  assert.equal((await js('window.folioDesktop.backup()')).saved,true);
  await request('/api/local/notes/'+original.id,{...await note(original.id),title:'Newer current workspace'},'PUT');
  confirmation=0;
  assert.equal((await js('window.folioDesktop.restore()')).canceled,true);
  assert.equal((await note(original.id)).title,'Newer current workspace');
  assert.equal(confirmations,1);
  console.log('PASS: backup cancellation retains the current workspace and defaults to Cancel.');
  chosen=path.join(directory,'invalid.zip');fs.writeFileSync(chosen,'not a zip');
  await assert.rejects(js('window.folioDesktop.restore()'));
  assert.equal((await note(original.id)).title,'Newer current workspace');
  assert.equal(confirmations,1);
  chosen=path.join(directory,'verified.zip');confirmation=1;
  const beforeRestoreDraft=JSON.stringify({...await note(original.id),title:'Unsaved before restore'});await js(`window.folioDesktop.noteDrafts.set(${JSON.stringify(original.id)},${JSON.stringify(beforeRestoreDraft)})`);
  const oldOrigin=window.webContents.getURL();
  await js("localStorage.setItem('folio-note-draft:stability','sample');window.folioDesktop.restore().catch(e=>console.error(e.message));true");
  await wait(()=>window.webContents.getURL().startsWith('http://')&&window.webContents.getURL()!==oldOrigin,'restore backend restart');
  await wait(async()=>{try{return (await note(original.id)).title==='Stability original';}catch{return false;}},'restored note');
  assert.equal(fs.existsSync(path.join(directory,'restore-journal.json')),false);
  assert.ok((await fsp.readdir(directory)).some(name=>name.startsWith('data-before-restore-')));
  assert.ok((await fsp.readdir(path.join(directory,'restore-backups'))).some(name=>name.endsWith('.zip')));
  assert.equal(children[0].child.exitCode,0);
  assert.equal(await js(`window.folioDesktop.noteDrafts.get(${JSON.stringify(original.id)})`),null);const archivedDraftFolder=(await fsp.readdir(directory)).find(name=>name.startsWith('note-drafts-before-restore-'));assert.ok(archivedDraftFolder);assert.equal(await fsp.readFile(path.join(directory,archivedDraftFolder,original.id+'.json'),'utf8'),beforeRestoreDraft);
  console.log('PASS: verified restore retains a recovery backup and previous data, restarts the backend, and preserves record IDs/content.');
  chosen=path.join(directory,'unreadable-database.zip');fs.writeFileSync(chosen,require('./backup-fixture.cjs').unreadableDatabaseBackup());
  const failedRestoreDraft=JSON.stringify({...await note(original.id),title:'Draft retained through failed restore'});await js(`window.folioDesktop.noteDrafts.set(${JSON.stringify(original.id)},${JSON.stringify(failedRestoreDraft)})`);
  const beforeFailedRestore=children.length,previousOrigin=window.webContents.getURL();
  await js("window.folioDesktop.restore().catch(e=>console.error(e.message));true");
  await wait(()=>children.length===beforeFailedRestore+2&&window.webContents.getURL().startsWith('http://')&&window.webContents.getURL()!==previousOrigin,'failed restore recovers the prior backend');
  await wait(async()=>{try{return (await note(original.id)).title==='Stability original'&&!fs.existsSync(path.join(directory,'restore-journal.json'));}catch{return false;}},'prior data available after failed startup');
  assert.ok((await fsp.readdir(directory)).some(name=>name.startsWith('data-failed-restore-')));
  assert.equal(await js(`window.folioDesktop.noteDrafts.get(${JSON.stringify(original.id)})`),failedRestoreDraft);
  console.log('PASS: successful restore archives unsaved drafts without deletion; failed restore retains the active draft.');
  console.log('PASS: checksum-valid backup with an unreadable database fails startup and recovers the previous workspace without losing records.');
  console.log('Actual sleep/wake remains a separate device check.');
  console.log('Disposable workspace:',directory);
  finished=true;
  app.quit();
}
if(!process.argv.includes('--second-instance'))app.whenReady().then(run).catch(error=>{console.error(error);process.exitCode=1;app.quit();});
