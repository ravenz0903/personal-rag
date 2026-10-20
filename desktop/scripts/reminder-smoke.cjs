// Actual private HTTP reminder queue with bundled Java and disposable records.
// Delivery acknowledgments are simulated; Windows toast/sleep behavior is separate.
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {startBackend,waitForBackend}=require('../runtime.cjs');
async function main(){
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'folio-reminder-queue-'));
  let backend;
  try{
    backend=await startBackend(path.resolve(__dirname,'../resources'),directory);await waitForBackend(backend);
    async function request(route,body,method=body?'POST':'GET'){
      const response=await fetch(backend.origin+route,{method,headers:{'X-Folio-Desktop-Token':backend.token,'Content-Type':'application/json'},...(body?{body:JSON.stringify(body)}:{})});
      assert.equal(response.ok,true,await response.clone().text());return response.json();
    }
    const at=new Date(Date.now()+15000).toISOString();
    for(let i=0;i<22;i++){
      const event=i===21;
      const payload=event?{title:'Queued event',startAt:new Date(Date.now()+3600000).toISOString(),endAt:new Date(Date.now()+7200000).toISOString(),remindAt:at}:{title:'Queued task '+i,priority:'LOW',remindAt:at};
      const draft=await request('/api/capture/structured',{domain:'ACADEMICS',entityType:event?'EVENT':'TASK',payload});
      await request('/api/capture/commit/'+draft.confirmationToken,{});
    }
    await new Promise(resolve=>setTimeout(resolve,Math.max(0,Date.parse(at)-Date.now()+100)));
    const first=await request('/api/desktop/reminders/claim');assert.equal(first.length,20);
    const second=await request('/api/desktop/reminders/claim');assert.equal(second.length,2);
    assert.deepEqual(await request('/api/desktop/reminders/claim'),[]);
    const all=[...first,...second];assert.equal(new Set(all.map(row=>row.kind+row.taskId)).size,22);
    assert.equal(all.filter(row=>row.kind==='event').length,1);
    for(const item of all){
      assert.equal((await request(`/api/desktop/reminders/eligible?id=${item.taskId}&kind=${item.kind}&at=${encodeURIComponent(item.remindAt)}`)).eligible,true);
      await request('/api/desktop/reminders/delivered',item);
    }
    assert.deepEqual(await request('/api/desktop/reminders/claim'),[]);
    console.log('PASS: 22 due task/event reminders drain in 20+2 batches; leases and delivered records do not block or duplicate later work.');
    const cancelAt=new Date(Date.now()+6000).toISOString();
    for(const title of ['Complete task','Archive task','Reschedule task','Clear task','Archive event','Reschedule event','Clear event']){
      const event=title.endsWith('event');
      const payload=event?{title,startAt:new Date(Date.now()+3600000).toISOString(),endAt:new Date(Date.now()+7200000).toISOString(),remindAt:cancelAt}:{title,priority:'LOW',remindAt:cancelAt};
      const draft=await request('/api/capture/structured',{domain:'ACADEMICS',entityType:event?'EVENT':'TASK',payload});await request('/api/capture/commit/'+draft.confirmationToken,{});
    }
    await new Promise(resolve=>setTimeout(resolve,Math.max(0,Date.parse(cancelAt)-Date.now()+100)));
    const cancelBatch=await request('/api/desktop/reminders/claim');assert.equal(cancelBatch.length,7);
    const movedAt=new Date(Date.now()+15000).toISOString();
    for(const item of cancelBatch){
      const record=await request(`/api/local/open/${item.kind}/${item.taskId}`);
      if(item.kind==='event'){
        await request('/api/local/calendar/'+record.id,{...record,...(record.title.startsWith('Archive')?{archived:true}:{remindAt:record.title.startsWith('Clear')?null:movedAt})},'PUT');
      }else if(record.title.startsWith('Archive')||record.title.startsWith('Complete')){
        await request('/api/local/tasks/'+record.id,{domain:record.domain,version:record.version,...(record.title.startsWith('Archive')?{archived:true}:{status:'COMPLETED'})},'PUT');
      }else{
        const draft=await request('/api/capture/structured',{domain:record.domain,entityType:'TASK_UPDATE',payload:{taskId:record.id,expectedVersion:record.version,title:record.title,priority:record.priority,...(record.title.startsWith('Reschedule')?{remindAt:movedAt}:{})}});
        await request('/api/capture/commit/'+draft.confirmationToken,{});
      }
      assert.equal((await request(`/api/desktop/reminders/eligible?id=${item.taskId}&kind=${item.kind}&at=${encodeURIComponent(item.remindAt)}`)).eligible,false);
    }
    assert.deepEqual(await request('/api/desktop/reminders/claim'),[]);
    console.log('PASS: task completion/archive, event archive and task/event reminder clear/reschedule invalidate already claimed old deliveries.');
    const preferences=await request('/api/local/settings');await request('/api/local/settings',{...preferences,remindersEnabled:false},'PUT');
    await new Promise(resolve=>setTimeout(resolve,Math.max(0,Date.parse(movedAt)-Date.now()+100)));
    assert.deepEqual(await request('/api/desktop/reminders/claim'),[]);
    await request('/api/local/settings',{...preferences,remindersEnabled:true},'PUT');
    const moved=await request('/api/desktop/reminders/claim');assert.equal(moved.length,2);assert.deepEqual(new Set(moved.map(item=>item.kind)),new Set(['task','event']));
    for(const item of moved){assert.equal(Date.parse(item.remindAt),Date.parse(movedAt));await request('/api/desktop/reminders/delivered',item);}
    assert.deepEqual(await request('/api/desktop/reminders/claim'),[]);
    console.log('PASS: disabling reminders suppresses due work; re-enabling claims only the two new rescheduled task/event times.');
    console.log('Windows delivery is not established by simulated acknowledgment.');
  }finally{
    if(backend){
      const stopped=new Promise(resolve=>{if(backend.child.exitCode!==null)resolve();else backend.child.once('exit',resolve);});
      await fetch(backend.origin+'/api/desktop/shutdown',{method:'POST',headers:{'X-Folio-Desktop-Token':backend.token}}).catch(()=>{});
      await Promise.race([stopped,new Promise(resolve=>setTimeout(resolve,15000))]);
      if(backend.child.exitCode===null){backend.child.kill();throw Error('Reminder smoke backend did not shut down.');}
    }
    console.log('Disposable workspace:',directory);
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
