// Run with Electron. Uses disposable records and the actual sandboxed renderer.
require('../logging.cjs').protectOutput();
const { app, BrowserWindow, session, ipcMain } = require('electron');
const fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const { startBackend, waitForBackend } = require('../runtime.cjs');
const { saveResponse, stageBackup } = require('../backup.cjs');
let backend, window, directory;
app.on('window-all-closed', () => {});
async function wait(check, label, timeout = 15000) { const until = Date.now() + timeout; while (Date.now() < until) { if (await check()) return; await new Promise(r => setTimeout(r, 100)); } throw new Error('Timed out: ' + label); }
const js = code => window.webContents.executeJavaScript(code, true);
const click = (selector, title) => js(`(() => {const button=[...document.querySelectorAll(${JSON.stringify(selector)})].find(b=>b.textContent.trim().startsWith(${JSON.stringify(title)}));if(!button)throw Error('Missing button: '+${JSON.stringify(title)});button.click();})()`);
async function request(route, body, method = body ? 'POST' : 'GET') { const response = await fetch(backend.origin + route, { method, headers: { 'X-Folio-Desktop-Token': backend.token, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) }); if (!response.ok) throw new Error(await response.text()); return response.json(); }
async function run() {
  directory = await fs.mkdtemp(path.join(os.tmpdir(), 'folio-ui-'));
  app.setPath('userData', path.join(directory, 'electron'));
  backend = await startBackend(path.resolve(__dirname, '../resources'), directory); await waitForBackend(backend);
  const errors = [], outgoing = [];
  let failNoteSave=false, holdTaskReads=false; const heldTaskReads=[];
  session.defaultSession.webRequest.onBeforeRequest((details,callback)=>{if(holdTaskReads&&details.method==='GET'&&new URL(details.url).pathname==='/api/local/tasks'){heldTaskReads.push(callback);return;}callback({cancel:failNoteSave&&details.method==='PUT'&&details.url.includes('/api/local/notes/')});});
  session.defaultSession.webRequest.onBeforeSendHeaders((details, callback) => { if (new URL(details.url).origin === backend.origin) details.requestHeaders['X-Folio-Desktop-Token'] = backend.token; else outgoing.push(details.url); callback({ requestHeaders: details.requestHeaders }); });
  ipcMain.handle('folio:settings', () => ({ dataPath: directory, notificationsSupported: false, runAtLogin: false }));
  for (const [domain, title] of [['ACADEMICS','Study physics'],['PERSONAL','Private journal task']]) { const draft = await request('/api/capture/structured', { domain, entityType: 'TASK', payload: { title, priority: 'MEDIUM', tags: ['study'], recurrence: 'NONE' } }); await request('/api/capture/commit/' + draft.confirmationToken, {}); }
  const parent=(await request('/api/local/tasks?domain=ACADEMICS')).find(task=>task.title==='Study physics');
  const childPreview=await request('/api/capture/structured',{domain:'ACADEMICS',entityType:'TASK',payload:{title:'Physics subtask',priority:'MEDIUM',parentId:parent.id}});await request('/api/capture/commit/'+childPreview.confirmationToken,{});
  const child=(await request('/api/local/tasks?domain=ACADEMICS')).find(task=>task.title==='Physics subtask');
  const note = await request('/api/local/notes', { domain: 'ACADEMICS', title: 'Physics notebook', tags: ['physics'], content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Momentum is conserved.' }] }] } });
  await request('/api/local/notes',{domain:'PERSONAL',title:'Private notebook',tags:[],content:{type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'Private test content'}]}]}});
  window = new BrowserWindow({ show: false, width: 1280, height: 850, webPreferences: { preload: path.resolve(__dirname, '../preload.cjs'), sandbox: true, contextIsolation: true, nodeIntegration: false, backgroundThrottling: false, offscreen: true } });
  window.webContents.on('console-message', details => { if (details.level >= 3) errors.push(details.message); });
  holdTaskReads=true;await window.loadURL(backend.origin+'/?record='+child.id+'&kind=task');
  await wait(()=>js(`!!document.querySelector('select[name=parentId]')`),'subtask editor before task list');
  assert.equal(await js(`document.querySelector('select[name=parentId]').value`),parent.id);
  assert.equal(await js(`new FormData(document.querySelector('.modal-card form')).get('parentId')`),parent.id);
  holdTaskReads=false;heldTaskReads.splice(0).forEach(callback=>callback({}));
  await wait(()=>js(`document.querySelector('select[name=parentId]')?.selectedOptions[0]?.textContent.includes('Study physics')`),'parent options loaded');
  assert.equal(await js(`document.querySelector('select[name=parentId]').value`),parent.id);
  await js(`document.querySelector('.modal-card form').requestSubmit()`);await wait(()=>js(`!!document.querySelector('.review-fields')`),'subtask review');await click('.modal-actions button','Approve & save');await wait(()=>js(`!document.querySelector('.modal-card')`),'subtask saved');
  assert.equal((await request('/api/local/open/task/'+child.id)).parentId,parent.id);
  console.log('PASS: delayed task-list loading preserves the subtask parent before and after loading, review, and save.');
  await window.loadURL(backend.origin); await wait(() => js(`!!document.querySelector('.nav-item')`), 'workspace startup');
  assert.equal(await js(`document.body.textContent.includes('Private journal task')`), false);
  await click('.nav-item','Tasks'); await wait(() => js(`!!document.querySelector('.local-task')`), 'task list'); await click('.local-toolbar button','Board'); await wait(() => js(`document.querySelectorAll('.board-column').length===3`), 'board');
  const output = path.resolve(__dirname, '../../target/ui-checks'); await fs.mkdir(output, { recursive: true }); await new Promise(r => setTimeout(r, 250)); await fs.writeFile(path.join(output,'board.png'), (await window.webContents.capturePage()).toPNG());
  await js(`document.querySelector('.local-task>.task-edit').focus();document.querySelector('.local-task>.task-edit').click()`);await wait(()=>js(`!!document.querySelector('.modal-card form')`),'keyboard task editor');assert.equal(await js(`document.querySelector('.modal-card').contains(document.activeElement)`),true);await js(`(()=>{const controls=[...document.querySelectorAll('.modal-card button:not(:disabled),.modal-card input:not([type=hidden]):not(:disabled),.modal-card select:not(:disabled),.modal-card textarea:not(:disabled)')];controls.at(-1).focus();window.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',bubbles:true,cancelable:true}));})()`);assert.equal(await js(`document.activeElement===document.querySelector('.modal-card button')`),true);await js(`window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`);await wait(()=>js(`!document.querySelector('.modal-card')`),'keyboard editor close');assert.equal(await js(`document.activeElement.matches('.task-edit')`),true);console.log('PASS: task dialog receives focus, traps Tab, and returns focus to its opener on Escape.');
  await js(`document.querySelector('.local-task>.task-edit').click()`);await wait(()=>js(`!!document.querySelector('.modal-card form')`),'task editor');await js(`(()=>{const input=document.querySelector('input[name=title]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'Study physics revised');input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.modal-card form').requestSubmit();})()`);await wait(()=>js(`!!document.querySelector('.review-fields')`),'task approval preview');await click('.modal-actions button','Approve & save');await wait(()=>js(`!document.querySelector('.modal-card')&&document.querySelector('.local-task')?.textContent.includes('Study physics revised')`),'approved task update');
  await click('.domain-tabs button','Personal');await wait(()=>js(`document.querySelector('.local-task')?.textContent.includes('Private journal task')`),'personal task');await click('.domain-tabs button','Academics + Career');await wait(()=>js(`document.querySelector('.domain-tabs .active')?.textContent.includes('Academics + Career')`),'combined scope');assert.equal(await js(`document.body.textContent.includes('Private journal task')`),false);
  await click('.nav-item','Notes'); await wait(() => js(`!!document.querySelector('.notes-list button')`), 'notes'); await click('.notes-list button','Physics notebook'); await wait(() => js(`!!document.querySelector('.tiptap')`), 'editor');
  await js(`document.querySelector('.tiptap').focus()`); window.webContents.insertText('Offline edit. ');
  await wait(async () => (await request(`/api/local/notes/${note.id}?domain=ACADEMICS`)).plainText.includes('Offline edit.'), 'note autosave');
  failNoteSave=true;await js(`document.querySelector('.tiptap').focus()`);window.webContents.insertText('Recovered edit. ');
  await wait(()=>js(`document.querySelector('.save-status')?.textContent.includes('Couldn’t save')`),'failed save status');assert.equal(await js(`Object.keys(localStorage).filter(k=>k.startsWith('folio-note-draft:')).length`),1);
  failNoteSave=false;await window.loadURL(backend.origin);await wait(()=>js(`!!document.querySelector('.nav-item')`),'reload');await click('.nav-item','Notes');await wait(()=>js(`!!document.querySelector('.notes-list button')`),'notes after reload');await click('.notes-list button','Physics notebook');await wait(()=>js(`document.querySelector('.save-status')?.textContent.includes('Recovered a local draft')`),'recovered draft');assert.equal((await request(`/api/local/notes/${note.id}?domain=ACADEMICS`)).plainText.includes('Recovered edit.'),false);await click('.local-toolbar button','Retry save');await wait(async()=>(await request(`/api/local/notes/${note.id}?domain=ACADEMICS`)).plainText.includes('Recovered edit.'),'retry saves recovered draft');
  console.log('PASS: failed autosave retains a draft, reload recovers it without automatic overwrite, and explicit retry saves it.');
  await new Promise(r => setTimeout(r, 250)); await fs.writeFile(path.join(output,'notes.png'), (await window.webContents.capturePage()).toPNG());
  await click('.domain-tabs button','Personal');await wait(()=>js(`document.querySelector('.notes-list')?.textContent.includes('Private notebook')`),'personal notes');await click('.notes-list button','Private notebook');await wait(()=>js(`document.querySelector('.note-editor')?.textContent.includes('Private test content')`),'personal note editor');await click('.domain-tabs button','Academics + Career');await wait(()=>js(`document.querySelector('.domain-tabs .active')?.textContent.includes('Academics + Career')`),'combined notes scope');assert.equal(await js(`document.body.textContent.includes('Private notebook')||document.body.textContent.includes('Private test content')`),false);console.log('PASS: switching out of Personal hides its tasks and open note immediately, before replacement reads finish.');
  await click('.nav-item','Calendar'); await wait(() => js(`document.querySelectorAll('.local-calendar-day').length===42`), 'month'); await click('.local-toolbar button','Week'); assert.equal(await js(`document.querySelectorAll('.local-calendar-day').length`),7); await click('.local-toolbar button','Day'); assert.equal(await js(`document.querySelectorAll('.calendar-slot').length`),24); await click('.local-toolbar button','Agenda'); assert.equal(await js(`document.querySelectorAll('.local-calendar-day').length`),30); await click('.local-toolbar button','Month');
  await click('.local-toolbar button','+ Event'); await wait(() => js(`!!document.querySelector('.modal-card form')`), 'event form');
  await js(`(() => {const input=document.querySelector('input[name=title]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'Offline study session');input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('.modal-card form').requestSubmit();})()`);
  await wait(async () => (await request('/api/local/calendar?domain=ACADEMICS')).some(e => e.title === 'Offline study session'), 'event save');
  await wait(() => js(`!document.querySelector('.modal-card')`),'event form closes');await new Promise(r => setTimeout(r, 250)); await fs.writeFile(path.join(output,'calendar.png'), (await window.webContents.capturePage()).toPNG());
  await js(`document.querySelector('.search-launch').click()`); await wait(() => js(`!!document.querySelector('[aria-label="Search workspace"]')`), 'search');
  await js(`(() => {const input=document.querySelector('[aria-label="Search workspace"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'Physics');input.dispatchEvent(new Event('input',{bubbles:true}));})()`); await wait(() => js(`document.querySelector('.workspace-results')?.textContent.includes('Physics notebook')`), 'search result');
  await js(`document.querySelector('.workspace-results button').click()`); await wait(() => js(`!!document.querySelector('.note-editor')`), 'open actual search record');
  // A second editor can change a record while this form is open. Preserve the
  // user's draft, display the conflict inside the dialog, and never overwrite it.
  await request('/api/workspace/courses', { code: 'SMOKE101', name: 'Concurrency course', term: 'Autumn', targetGrade: 80 });
  const course = (await request('/api/local/records/course')).find(r => r.code === 'SMOKE101');
  async function searchCourse() {
    await js(`document.querySelector('.search-launch').click()`);
    await wait(() => js(`!!document.querySelector('[aria-label="Search workspace"]')`), 'course search');
    await js(`(() => {const input=document.querySelector('[aria-label="Search workspace"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'Concurrency');input.dispatchEvent(new Event('input',{bubbles:true}));})()`);
    await wait(() => js(`document.querySelector('.workspace-results')?.textContent.includes('Concurrency')`), 'course result');
    await click('.workspace-results button', 'course');
    await wait(() => js(`!!document.querySelector('.modal-card input[name=code]')`), 'course editor from search');
  }
  await searchCourse();
  await request(`/api/local/records/course/${course.id}`, { ...course, name: 'Concurrency newer version' }, 'PUT');
  await js(`document.querySelector('.modal-card input[name=name]').value='Concurrency local draft';document.querySelector('.modal-card form').requestSubmit()`);
  await wait(() => js(`!!document.querySelector('.modal-card [role=alert]')`), 'conflict inside course dialog');
  assert.equal(await js(`document.querySelector('.modal-card input[name=name]').value`), 'Concurrency local draft');
  assert.equal((await request('/api/local/records/course')).find(r => r.id === course.id).name, 'Concurrency newer version');
  await js(`document.querySelector('.modal-card [aria-label="Close dialog"]').click()`);
  await wait(() => js(`!document.querySelector('.modal-card')&&document.querySelector('.record-actions')?.textContent.includes('Concurrency newer version')`), 'reload after dismissing conflict');
  await searchCourse();
  assert.equal(await js(`document.querySelector('.modal-card input[name=name]').value`), 'Concurrency newer version');
  await js(`document.querySelector('.modal-card input[name=name]').value='Concurrency reviewed version';document.querySelector('.modal-card form').requestSubmit()`);
  await wait(() => js(`!document.querySelector('.modal-card')&&document.querySelector('.record-actions')?.textContent.includes('Concurrency reviewed version')`), 'saved search editor stays closed after list refresh');
  assert.equal((await request('/api/local/records/course')).find(r => r.id === course.id).name, 'Concurrency reviewed version');
  console.log('PASS: stale course edits retain the draft and show an in-dialog error, preserve the newer record, reload on dismissal, and stay closed after a successful search-origin save.');
  await click('.nav-item','Calendar'); await wait(() => js(`!!document.querySelector('.calendar-entry.event')`), 'saved event');
  await click('.calendar-entry.event', '');
  await wait(() => js(`!!document.querySelector('.modal-card input[name=title]')`), 'event editor');
  const event = (await request('/api/local/calendar?domain=ACADEMICS')).find(e => e.title === 'Offline study session');
  await request(`/api/local/calendar/${event.id}`, { ...event, title: 'Newer study session' }, 'PUT');
  await js(`document.querySelector('.modal-card input[name=title]').value='Unsaved study session';document.querySelector('.modal-card form').requestSubmit()`);
  await wait(() => js(`!!document.querySelector('.modal-card [role=alert]')`), 'event conflict inside dialog');
  assert.equal(await js(`document.querySelector('.modal-card input[name=title]').value`), 'Unsaved study session');
  assert.equal((await request('/api/local/calendar?domain=ACADEMICS')).find(e => e.id === event.id).title, 'Newer study session');
  await js(`document.querySelector('.modal-card [aria-label="Close dialog"]').click()`);
  await wait(() => js(`!document.querySelector('.modal-card')&&document.querySelector('.local-calendar')?.textContent.includes('Newer study session')`), 'calendar refreshed after conflict');
  console.log('PASS: event conflict is visible in its dialog, preserves the draft and newer saved record, and refreshes after dismissal.');
  await click('.nav-item','Progress'); await wait(() => js(`!!document.querySelector('.completion-chart')`), 'progress');
  await click('.nav-item','Settings'); await wait(() => js(`!!document.querySelector('input[name=timezone]')`), 'settings');
  await js(`window.dispatchEvent(new KeyboardEvent('keydown',{key:'k',ctrlKey:true,bubbles:true}))`);await wait(()=>js(`!!document.querySelector('[aria-label="Search workspace"]')`),'keyboard search');await js(`document.querySelector('.modal-card').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))`);await wait(()=>js(`!document.querySelector('.modal-card')`),'keyboard dismiss');window.setSize(740,650);await click('.nav-item','Tasks');await wait(()=>js(`!!document.querySelector('.local-task')`),'narrow task view');await new Promise(r=>setTimeout(r,250));assert.equal(await js(`document.documentElement.scrollWidth<=document.documentElement.clientWidth`),true);await fs.writeFile(path.join(output,'narrow.png'),(await window.webContents.capturePage()).toPNG());
  assert.equal(await js(`getComputedStyle(document.documentElement).scrollbarColor`),'auto');
  assert.equal(await js(`getComputedStyle(document.documentElement,'::-webkit-scrollbar').width`),'4px');
  assert.deepEqual(errors.filter(message=>!message.includes('ERR_BLOCKED_BY_CLIENT')), []); assert.deepEqual(outgoing, []);console.log('Scrollbar:',await js("JSON.stringify({supports:CSS.supports('selector(::-webkit-scrollbar)'),width:getComputedStyle(document.querySelector('.page-content'),'::-webkit-scrollbar').width})"));
  const backup = path.join(directory,'roundtrip.zip'); await saveResponse(await fetch(backend.origin + '/api/desktop/backup', { headers: { 'X-Folio-Desktop-Token': backend.token } }), backup);
  const staged = await stageBackup(backup, directory); assert.ok(await fs.stat(path.join(staged.data, 'tracker.mv.db')));
  const restored=await startBackend(path.resolve(__dirname,'../resources'),staged.staging);
  try{
    await waitForBackend(restored);const result=await fetch(restored.origin+`/api/local/notes/${note.id}?domain=ACADEMICS`,{headers:{'X-Folio-Desktop-Token':restored.token}});assert.equal(result.status,200);assert.ok((await result.json()).plainText.includes('Offline edit.'));
    console.log('PASS: a streamed backup restores into a separate bundled-runtime workspace with the original note ID and saved edit.');
  }finally{const exited=new Promise(r=>restored.child.exitCode!==null?r():restored.child.once('exit',r));await fetch(restored.origin+'/api/desktop/shutdown',{method:'POST',headers:{'X-Folio-Desktop-Token':restored.token}}).catch(()=>{});await Promise.race([exited,new Promise(r=>setTimeout(r,12000))]);if(restored.child.exitCode===null)restored.child.kill();}
  console.log('PASS: sandboxed desktop UI, task board, private-area exclusion, Tiptap autosave, four calendar views, event creation, search opening records, progress, preferences, no external renderer requests, backup validation.'); console.log('Screenshots:', output);
}
app.whenReady().then(run).then(() => cleanup(0)).catch(async error => { console.error(error); if (window) console.error(await js('document.body.innerText')); await cleanup(1); });
async function cleanup(code) { window?.destroy(); if (backend) { const exited = new Promise(r => backend.child.exitCode !== null ? r() : backend.child.once('exit',r)); await fetch(backend.origin + '/api/desktop/shutdown',{method:'POST',headers:{'X-Folio-Desktop-Token':backend.token}}).catch(()=>{});await Promise.race([exited,new Promise(r=>setTimeout(r,12000))]); if (backend.child.exitCode === null) backend.child.kill(); } console.log('Disposable workspace:', directory); app.exit(code); }
