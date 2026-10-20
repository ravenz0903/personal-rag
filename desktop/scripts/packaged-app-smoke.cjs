// Direct packaged Folio.exe acceptance in an explicitly isolated data directory.
// Remote debugging is enabled only for this local QA invocation.
const fs=require('node:fs'),fsp=require('node:fs/promises'),path=require('node:path'),os=require('node:os'),net=require('node:net');
const {spawn}=require('node:child_process'),assert=require('node:assert/strict'),asar=require('@electron/asar');
const executable=path.resolve(process.argv[2]||'');
let child,socket,origin,directory;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function port(){const server=net.createServer();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const value=server.address().port;await new Promise(resolve=>server.close(resolve));return value;}
async function stopped(){
  socket?.close();socket=null;
  if(child&&child.exitCode===null&&child.signalCode===null){const exited=new Promise(resolve=>child.once('exit',resolve));child.kill();await exited;}
  if(origin){const until=Date.now()+20000;let gone=false;while(Date.now()<until){try{await fetch(origin+'/api/health',{signal:AbortSignal.timeout(1000)});}catch{gone=true;break;}await delay(200);}assert.equal(gone,true,'Backend exits after packaged parent termination');}
  child=null;origin=null;
}
async function launch(){
  const debugPort=await port();
  const environment={...process.env};for(const key of Object.keys(environment))if(/^(PATH|JAVA_HOME|ELECTRON_RUN_AS_NODE|NODE_OPTIONS)$/i.test(key))delete environment[key];
  const windows=environment.SystemRoot||'C:\\Windows';environment.PATH=path.join(windows,'System32')+';'+windows;environment.JAVA_HOME=path.join(directory,'absent-development-java');
  child=spawn(executable,['--hidden','--user-data-dir='+directory,'--remote-debugging-address=127.0.0.1','--remote-debugging-port='+debugPort],{windowsHide:true,env:environment,stdio:['ignore','pipe','pipe']});
  const log=fs.createWriteStream(path.join(directory,'packaged-process.log'),{flags:'a'});child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});child.once('close',()=>log.end());
  const until=Date.now()+95000;let target;
  while(Date.now()<until){if(child.exitCode!==null||child.signalCode!==null)throw Error('Packaged app exited before startup');try{const pages=await(await fetch(`http://127.0.0.1:${debugPort}/json/list`)).json();target=pages.find(page=>page.type==='page'&&page.url.startsWith('http://127.0.0.1:'));if(target)break;}catch{}await delay(150);}
  assert.ok(target,'Packaged renderer starts');origin=new URL(target.url).origin;
  socket=new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
  let counter=0;const pending=new Map();
  socket.addEventListener('message',event=>{const message=JSON.parse(event.data);const entry=pending.get(message.id);if(!entry)return;pending.delete(message.id);clearTimeout(entry.timer);message.error?entry.reject(Error(message.error.message)):entry.resolve(message.result);});
  async function evaluate(expression){const id=++counter;const result=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>{pending.delete(id);reject(Error('Packaged evaluation timed out'));},15000);pending.set(id,{resolve,reject,timer});socket.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,awaitPromise:true,returnByValue:true,userGesture:true}}));});if(result.exceptionDetails)throw Error(result.exceptionDetails.text+': '+JSON.stringify(result.exceptionDetails.exception));return result.result.value;}
  const settings=await evaluate('window.folioDesktop.settings()');assert.equal(path.resolve(settings.dataPath),directory,'Explicit data directory must isolate the real app');
  const config=await evaluate("fetch('/api/config').then(r=>r.json())");assert.equal(config.aiEnabled,false);assert.equal(config.desktopMode,true);
  const ready=Date.now()+15000;while(Date.now()<ready){if(await evaluate("!!document.querySelector('.nav-item')"))return evaluate;await delay(100);}throw Error('Packaged interface did not initialize');
}
async function main(){
  assert.ok(fs.existsSync(executable),'Supply the packaged Folio.exe');
  const mainSource=asar.extractFile(path.join(path.dirname(executable),'resources/app.asar'),'main.cjs').toString();
  assert.match(mainSource,/hasSwitch\('user-data-dir'\)/,'Refuse a build that overrides the disposable data directory');
  assert.doesNotThrow(()=>JSON.parse(asar.extractFile(path.join(path.dirname(executable),'resources/app.asar'),'ai/release-qualification-lock.json').toString()),'Required qualification configuration must be packaged');
  if(process.argv[3]&&!process.argv[3].startsWith('--')){
    directory=path.resolve(process.argv[3]);
    const marker=JSON.parse(await fsp.readFile(path.join(directory,'qa-workspace-marker.json'),'utf8'));
    assert.equal(marker.fixture,'folio-installer-qa','Explicit workspace must be a declared QA fixture');
  }else directory=await fsp.mkdtemp(path.join(os.tmpdir(),'folio-packaged-app-'));
  const recordFile=path.join(directory,'qa-record.json');
  const priorRecord=fs.existsSync(recordFile)?JSON.parse(await fsp.readFile(recordFile,'utf8')):null;
  const checkDraft=process.argv.includes('--draft-recovery');
  async function waitExpression(evaluate,expression,label){const until=Date.now()+15000;while(Date.now()<until){if(await evaluate(expression))return;await delay(100);}throw Error('Timed out: '+label);}
  async function openEditor(evaluate,note){await evaluate("[...document.querySelectorAll('.nav-item')].find(button=>button.textContent.trim()==='Notes').click()");await waitExpression(evaluate,`document.querySelector('.notes-list')?.textContent.includes(${JSON.stringify(note.title)})||false`,'note list');await evaluate(`[...document.querySelectorAll('.notes-list button')].find(button=>button.textContent.startsWith(${JSON.stringify(note.title)})).click()`);await waitExpression(evaluate,"!!document.querySelector('.note-title')",'note editor');}
  let evaluate=await launch();
  const note=priorRecord?priorRecord.note:await evaluate(`(async()=>{const response=await fetch('/api/local/notes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({domain:'ACADEMICS',title:'Packaged persistence check',tags:[],content:{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'Keep across packaged restart.'}]}]}})});if(!response.ok)throw Error('Create failed '+response.status);return response.json();})()`);
  if(!priorRecord)await evaluate(`(async()=>{const response=await fetch('/api/ingest/markdown',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({domain:'ACADEMICS',path:'installer-preserved.md',content:'# Installer preservation check'})});if(!response.ok)throw Error('Upload failed '+response.status);return response.json();})()`);
  const documents=await evaluate("fetch('/api/ingest/documents?domain=ACADEMICS').then(r=>r.json())");
  if(priorRecord){const previous=await evaluate(`fetch('/api/local/notes/${note.id}?domain=ACADEMICS').then(r=>r.json())`);assert.equal(previous.id,note.id);assert.equal(previous.plainText,note.plainText);assert.deepEqual(documents,priorRecord.documents);console.log('PASS: pre-existing note and document records preserved before this launch.');}
  else await fsp.writeFile(recordFile,JSON.stringify({note,documents},null,2));
  assert.ok(note.id);assert.ok(fs.existsSync(path.join(directory,'data/tracker.mv.db')));
  if(checkDraft){await openEditor(evaluate,note);await evaluate(`(()=>{const original=window.fetch;window.fetch=(url,options)=>String(url).includes('/api/local/notes/${note.id}')&&options?.method==='PUT'?Promise.reject(Error('Injected failed autosave')):original(url,options);const input=document.querySelector('.note-title');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'Unsent crash draft');input.dispatchEvent(new Event('input',{bubbles:true}));})()`);await waitExpression(evaluate,"document.querySelector('.save-status')?.textContent.includes('Couldn')||false",'failed save with recovery draft');console.log('Verified failed autosave before forced restart.');}
  await stopped();evaluate=await launch();
  const restored=await evaluate(`(async()=>{const response=await fetch('/api/local/notes/${note.id}?domain=ACADEMICS');const body=await response.json();if(!response.ok)throw Error('Reload failed '+response.status+': '+JSON.stringify(body));return body;})()`);assert.equal(restored.id,note.id);assert.equal(restored.plainText,note.plainText);
  assert.deepEqual(await evaluate("fetch('/api/ingest/documents?domain=ACADEMICS').then(r=>r.json())"),documents);
  if(checkDraft){assert.equal(restored.title,note.title,'Failed save must not mutate stored note');await openEditor(evaluate,note);assert.equal(await evaluate("document.querySelector('.note-title').value"),'Unsent crash draft','Recovery draft survives a different backend origin after process restart');assert.equal(await evaluate("document.querySelector('.save-status').textContent.includes('Recovered')"),true);await evaluate("[...document.querySelectorAll('.local-toolbar button')].find(button=>button.textContent==='Discard draft & reload').click()");await waitExpression(evaluate,`document.querySelector('.note-title')?.value===${JSON.stringify(note.title)}`,'draft discarded without database overwrite');console.log('PASS: failed autosave draft survives process restart and can be explicitly discarded without changing saved data.');}
  await evaluate("[...document.querySelectorAll('.nav-item')].find(button=>button.textContent.trim()==='Notes').click()");
  const until=Date.now()+15000;let visible=false;while(Date.now()<until){visible=await evaluate("document.querySelector('.notes-list')?.textContent.includes('Packaged persistence check')||false");if(visible)break;await delay(100);}assert.equal(visible,true,'Persisted note appears in actual packaged UI');
  await stopped();
  console.log('PASS: direct packaged Folio.exe starts with isolated userData, saves records, restarts with original IDs/content, renders persisted notes and cleans up backend after parent termination.');
  console.log('Forced parent termination is tested; graceful tray Quit is covered separately.');console.log('Disposable workspace:',directory);
  console.log('PASS: application child PATH contains only Windows directories; bundled Java works with an invalid development JAVA_HOME.');
}
main().catch(async error=>{console.error(error);await stopped().catch(cleanup=>console.error(cleanup));process.exitCode=1;});
