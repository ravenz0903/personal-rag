// Real launcher recovery from the persisted state of an interrupted replacement.
const {app,BrowserWindow,dialog}=require('electron');
const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const runtime=require('../runtime.cjs');
let directory,seedBackend;
const children=[];
async function stop(backend){
  if(!backend||backend.child.exitCode!==null)return;
  const exited=new Promise(resolve=>backend.child.once('exit',resolve));
  await fetch(backend.origin+'/api/desktop/shutdown',{method:'POST',headers:{'X-Folio-Desktop-Token':backend.token}});
  let timer;await Promise.race([exited,new Promise(resolve=>{timer=setTimeout(resolve,15000);})]);clearTimeout(timer);
  if(backend.child.exitCode===null){backend.child.kill();throw Error('Disposable backend shutdown failed.');}
}
async function run(){
  directory=await fs.mkdtemp(path.join(os.tmpdir(),'folio-interrupted-restore-'));
  seedBackend=await runtime.startBackend(path.resolve(__dirname,'../resources'),directory);await runtime.waitForBackend(seedBackend);
  async function request(route,body){const response=await fetch(seedBackend.origin+route,{method:body?'POST':'GET',headers:{'X-Folio-Desktop-Token':seedBackend.token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});assert.equal(response.ok,true,await response.clone().text());return response.json();}
  const note=await request('/api/local/notes',{domain:'ACADEMICS',title:'Before interrupted restore',tags:[],content:{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'Keep this original note.'}]}]}});
  await request('/api/ingest/markdown',{domain:'ACADEMICS',path:'recovery.md',content:'# Preserved original document'});
  const documents=await request('/api/ingest/documents?domain=ACADEMICS');assert.equal(documents.length,1);
  await stop(seedBackend);seedBackend=null;
  const prior='data-before-restore-'+Date.now();
  await fs.rename(path.join(directory,'data'),path.join(directory,prior));
  await fs.mkdir(path.join(directory,'data'));await fs.writeFile(path.join(directory,'data','tracker.mv.db'),Buffer.alloc(8192));
  await fs.writeFile(path.join(directory,'restore-journal.json'),JSON.stringify({prior}));
  const originalSetPath=app.setPath.bind(app);app.setPath=(name,value)=>originalSetPath(name,name==='userData'?directory:value);
  const start=runtime.startBackend;runtime.startBackend=async(...args)=>{const backend=await start(...args);children.push(backend);return backend;};
  dialog.showErrorBox=(title,message)=>{console.error(title,message);};
  process.argv.push('--hidden');const originalLog=console.log;require('../main.cjs');console.log=originalLog;
  const until=Date.now()+95000;let window;
  while(Date.now()<until){window=BrowserWindow.getAllWindows()[0];if(window?.webContents.getURL().startsWith('http://'))break;await new Promise(resolve=>setTimeout(resolve,100));}
  assert.ok(window?.webContents.getURL().startsWith('http://'),'Recovered launcher starts');
  const recovered=await window.webContents.executeJavaScript(`fetch('/api/local/notes/${note.id}?domain=ACADEMICS').then(r=>r.json())`);
  assert.equal(recovered.title,note.title);assert.equal(recovered.plainText,note.plainText);
  const recoveredDocuments=await window.webContents.executeJavaScript("fetch('/api/ingest/documents?domain=ACADEMICS').then(r=>r.json())");
  assert.deepEqual(recoveredDocuments,documents);
  assert.equal(await fs.stat(path.join(directory,'restore-journal.json')).catch(()=>null),null);
  assert.equal(await fs.stat(path.join(directory,prior)).catch(()=>null),null);
  const preserved=(await fs.readdir(directory)).find(name=>name.startsWith('data-interrupted-restore-'));assert.ok(preserved);
  assert.equal((await fs.stat(path.join(directory,preserved,'tracker.mv.db'))).size,8192);
  console.log('PASS: real launcher rolls back interrupted replacement before startup, preserves original note/document IDs and metadata, retains failed replacement and removes completed journal.');
  for(const backend of children)await stop(backend);
  console.log('Disposable workspace:',directory);app.quit();
}
app.whenReady().then(run).catch(async error=>{console.error(error);await stop(seedBackend).catch(()=>{});for(const backend of children)await stop(backend).catch(()=>{});app.exit(1);});
