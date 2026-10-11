const test=require('node:test');
const assert=require('node:assert/strict');
const {checkProposal,validDate}=require('../ai/proposals.cjs');
const context={workspaceId:'w1',area:'ALL',mode:'actions'};
test('proposal gate rejects document writes, ambiguous edits and inaccessible records',()=>{
  const create={action:'create_task',area:'ACADEMICS',title:'Read'};
  assert.deepEqual(checkProposal(create,context),[]);
  assert.ok(checkProposal(create,{...context,mode:'retrieval'}).includes('retrieval cannot write'));
  assert.ok(checkProposal({...create,area:'PERSONAL'},context).length);
  assert.ok(checkProposal(create,null).length);
  const edit={action:'edit_task',recordId:'t1',priority:'HIGH'};
  const record={id:'t1',workspaceId:'w1',area:'ACADEMICS'};
  assert.deepEqual(checkProposal(edit,{...context,matchedRecords:[record]}),[]);
  for(const matchedRecords of [[],[record,record],[{...record,workspaceId:'w2'}],[{...record,area:'PERSONAL'}],[{...record,id:'t2'}]]) assert.ok(checkProposal(edit,{...context,matchedRecords}).length);
});
test('proposal gate checks citation shape, source scope, abstention and invented fields',()=>{
  const answer={action:'answer',answer:'At noon.',citations:['S1'],abstain:false};
  const sources=[{id:'S1',area:'ACADEMICS',workspaceId:'w1'}];
  assert.deepEqual(checkProposal(answer,{...context,mode:'retrieval',sources}),[]);
  for(const bad of [{...answer,citations:'S1'},{...answer,citations:[{id:'S1'}]},{...answer,citations:['invented']},{...answer,abstain:undefined},{...answer,citations:[]},{...answer,recordId:'t1'}]) assert.ok(checkProposal(bad,{...context,sources}).length);
  assert.ok(checkProposal(answer,{...context,sources:[{...sources[0],workspaceId:'w2'}]}).length);
  assert.ok(checkProposal(answer,{...context,sources:[{...sources[0],area:'PERSONAL'}]}).length);
  assert.ok(checkProposal(answer,{...context,sources:[null]}).length);
  assert.deepEqual(checkProposal({action:'answer',answer:'Not supplied.',citations:[],abstain:true},context),[]);
});
test('proposal gate rejects nonexistent dates, zero-duration events and invalid priority',()=>{
  assert.equal(validDate('2028-02-29T12:15'),true);
  for(const value of ['2029-02-29T12:15','2028-04-31T08:00','2028-04-30T24:00','2028-04-30T08:00:00']) assert.equal(validDate(value),false);
  const event={action:'create_event',area:'CAREER',title:'Interview',startLocal:'2028-04-30T08:00',endLocal:'2028-04-30T08:30'};
  assert.deepEqual(checkProposal(event,context),[]);
  assert.ok(checkProposal({...event,endLocal:event.startLocal},context).length);
  assert.ok(checkProposal({action:'create_task',area:'ACADEMICS',title:'Read',priority:'high'},context).length);
});
test('application reminder proposals need a real reminder time, separately from deadline',()=>{
  const task={action:'create_task',area:'ACADEMICS',title:'Read',dueLocal:'2034-01-01T10:00'};
  assert.ok(checkProposal(task,{...context,reminderIntent:true}).includes('missing actual reminder'));
  assert.deepEqual(checkProposal({...task,remindLocal:'2034-01-01T09:00'},{...context,reminderIntent:true}),[]);
  assert.ok(checkProposal({...task,remindLocal:'2034-02-30T09:00'},context).includes('invalid reminder'));
});
