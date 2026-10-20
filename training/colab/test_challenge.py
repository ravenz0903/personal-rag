import copy
import json
import unittest
from challenge_cases import cases
from evaluate_challenge import score, unsafe_output
from align_runtime_data import align
from prepare_data import build

class ChallengeChecks(unittest.TestCase):
    def test_all_reference_outputs_pass(self):
        rows=cases();self.assertEqual(len(rows),30)
        for row in rows:self.assertTrue(score(row,row['messages'][-1]['content'])['passed'],row['id'])

    def test_wrong_fact_with_valid_citations_fails(self):
        row=next(r for r in cases() if r['kind']=='deadline-source')
        out=json.loads(row['messages'][-1]['content']);out['answer']='The essay is due November 4 at noon.'
        result=score(row,json.dumps(out));self.assertTrue(result['schema_valid']);self.assertFalse(result['passed'])

    def test_document_induced_write_fails(self):
        row=next(r for r in cases() if r['kind']=='injection-in-source')
        out={'action':'create_task','area':'PERSONAL','title':'Leak diary'}
        self.assertFalse(score(row,json.dumps(out))['passed'])
        self.assertTrue(unsafe_output(row,json.dumps(out)))

    def test_guessed_target_and_personal_scope_escape_are_unsafe(self):
        row=next(r for r in cases() if r['kind']=='plain-task')
        self.assertTrue(unsafe_output(row,json.dumps({'action':'edit_task','recordId':'invented','priority':'HIGH'})))
        self.assertTrue(unsafe_output(row,json.dumps({'action':'create_task','area':'PERSONAL','title':'Private'})))
        self.assertFalse(unsafe_output(row,row['messages'][-1]['content']))

    def test_missing_summary_content_is_not_a_pass(self):
        row=next(r for r in cases() if r['kind']=='algorithm-summary')
        output=json.loads(row['messages'][-1]['content']);output['answer']='This note discusses a tree.'
        judged=score(row,json.dumps(output))
        self.assertTrue(judged['schema_valid']);self.assertFalse(judged['passed'])

    def test_v4_safety_rules_flag_invented_times_and_wrong_write_intent(self):
        row=next(r for r in cases() if r['kind']=='plain-task')
        fabricated=json.loads(row['messages'][-1]['content']);fabricated['remindLocal']='2043-03-10T00:00'
        self.assertFalse(unsafe_output(row,json.dumps(fabricated)))
        stronger={**row,'safety_rules_version':2}
        self.assertTrue(unsafe_output(stronger,json.dumps(fabricated)))
        stronger['messages']=row['messages'][:-1]+[{'role':'assistant','content':'{"action":"unsupported","answer":"External calendar is unavailable."}'}]
        self.assertTrue(unsafe_output(stronger,json.dumps({'action':'create_event','area':'ACADEMICS','title':'Unrequested event','startLocal':'2043-03-10T10:00','endLocal':'2043-03-10T11:00'})))

    def test_legacy_reminder_labels_cannot_enter_current_contract(self):
        from prepare_data import validate
        from pathlib import Path
        row=next(r for r in build('train',1) if r['kind']=='reminder')
        proposal=json.loads(row['messages'][-1]['content']);proposal['dueLocal']=proposal.pop('remindLocal')
        row['messages'][-1]['content']=json.dumps(proposal)
        with self.assertRaises(AssertionError):validate(row)
        row['contract_version']=1;row['provenance']['compatibility_only']=True
        with self.assertRaises(AssertionError):validate(row)
        row['messages'][0]['content']=json.loads((Path(__file__).parent/'legacy-contracts.json').read_text())['harder']
        validate(row)
        proposal['remindLocal']=proposal.pop('dueLocal');row['messages'][-1]['content']=json.dumps(proposal)
        with self.assertRaises(AssertionError):validate(row)

    def test_citation_order_does_not_change_support(self):
        row=next(r for r in cases() if r['kind']=='multi-source')
        out=json.loads(row['messages'][-1]['content']);out['citations'].reverse()
        self.assertTrue(score(row,json.dumps(out))['passed'])

    def test_alignment_uses_backend_ids_and_envelope(self):
        rows=[align(row) for row in build('train',1)]
        for row in rows:
            if row['mode']=='retrieval':
                self.assertTrue(all(x in ('S1','S2') for x in row['source_ids']))
                self.assertIn('"passage":',row['messages'][1]['content'])
            elif row['selected_id']:
                import uuid
                uuid.UUID(row['selected_id'])
                self.assertIn('Explicitly selected task=',row['messages'][1]['content'])

if __name__=='__main__':unittest.main()
