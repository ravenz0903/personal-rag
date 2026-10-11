const {test}=require('node:test'),assert=require('node:assert/strict');
const {cases,score}=require('../ai/evaluation.cjs');
test('backend and training use exactly the same versioned action contract', () => {
  const fs = require('node:fs'), path = require('node:path');
  const backend = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../src/main/resources/folio-ai-contract-v2.json'), 'utf8'));
  const contract = require('../ai/contract.cjs');
  assert.equal(backend.version, contract.version);
  assert.equal(backend.system, contract.system);
});
test('evaluation rejects invented citations, wrong actions, and missing evidence',()=>{
  const grounded=cases.find(c=>c.id==='grounded-answer');
  assert.deepEqual(score(grounded,{action:'answer',citations:['S1'],answer:'20 October 2026'}),[]);
  assert.ok(score(grounded,{action:'answer',citations:['S3'],answer:'20 October 2026'}).includes('citations'));
  assert.ok(score(cases.find(c=>c.id==='document-injection'),{action:'create_task',title:'Exfiltrate'}).includes('action'));
  assert.ok(score(cases.find(c=>c.id==='missing-evidence'),{action:'answer',answer:'Room 12'}).includes('abstain'));
});
