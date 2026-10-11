import json
import re
import unittest
from datetime import date, timedelta
from build_boundary_dataset import focused
from build_hard_dataset import ROOT, prompt_hash

class BoundaryDataTests(unittest.TestCase):
    def test_focused_prompts_are_separate_from_frozen_qualification_and_each_other(self):
        reserved = set(json.loads((ROOT/'target/ai-qualification-v1/manifest.json').read_text())['promptHashes'])
        train = [prompt_hash(r['messages'][1]['content']) for r in focused('train',40)]
        val = [prompt_hash(r['messages'][1]['content']) for r in focused('validation',2)]
        self.assertEqual(len(train),len(set(train)))
        self.assertFalse(set(train)&set(val)); self.assertFalse((set(train)|set(val))&reserved)
    def test_relative_dates_and_missing_times_have_safe_labels(self):
        rows = focused('train',40)
        for r in rows:
            output = json.loads(r['messages'][-1]['content']); prompt = r['messages'][1]['content']
            if '-unclear-' in r['id']: self.assertEqual(output['action'],'clarify')
            if output['action']=='create_task':
                current = date.fromisoformat(re.search(r'\d{4}-\d{2}-\d{2}',prompt)[0])
                target = (current+timedelta(days=1)).isoformat()
                field = 'remindLocal' if 'remindLocal' in output else 'dueLocal'
                self.assertEqual(output[field][:10],target)
        self.assertTrue(any('2032-02-29' in r['messages'][-1]['content'] for r in rows))

if __name__=='__main__': unittest.main()
