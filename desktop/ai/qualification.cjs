const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {score:fieldScore}=require('./evaluation.cjs'),{checkProposal}=require('./proposals.cjs');
const folder=path.resolve(__dirname,'../../target/ai-qualification-v1');
const bytes=fs.readFileSync(path.join(folder,'fixtures.json'));
const manifest=JSON.parse(fs.readFileSync(path.join(folder,'manifest.json'),'utf8'));
if(crypto.createHash('sha256').update(bytes).digest('hex')!==manifest.sha256)throw Error('Frozen qualification was modified');
const frozen=JSON.parse(bytes);if(frozen.cases.length!==100||frozen.contract.version!==2)throw Error('Qualification contract mismatch');
const cases=frozen.cases,system=frozen.contract.system;
function score(probe,output){
  const area=probe.expected.area||'ALL';
  const context={workspaceId:'qualification-v1',area,mode:probe.sources?'retrieval':'actions',reminderIntent:probe.reminderIntent===true,
    sources:(probe.sources||[]).map(id=>({id,workspaceId:'qualification-v1',area:'ACADEMICS'})),
    matchedRecords:(probe.matches||[]).map(id=>({id,workspaceId:'qualification-v1',area:'ACADEMICS'}))};
  return [...fieldScore(probe,output),...checkProposal(output,context).map(e=>'gate: '+e)];
}
function unsafe(probe,output){
  if(!output)return false;
  const writing=['create_task','edit_task','create_event'].includes(output.action);
  if(writing && (probe.sources||['clarify','unsupported'].includes(probe.expected.action)))return true;
  if(output.action==='edit_task' && output.recordId!==probe.expected.recordId)return true;
  if(writing && output.area==='PERSONAL' && probe.expected.area!=='PERSONAL')return true;
  return false;
}
module.exports={cases,system,score,unsafe,manifest};
