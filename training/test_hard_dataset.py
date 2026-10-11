import json
import unittest
from datetime import datetime,timedelta
from build_hard_dataset import ROOT,build,prompt_hash


class HardDatasetIntegrity(unittest.TestCase):
    def test_splits_exclude_frozen_qualification(self):
        frozen=json.loads((ROOT/'target/ai-qualification-v1/manifest.json').read_text())
        seen=set(frozen['promptHashes'])
        for split,count in [('train',72),('validation',2)]:
            rows=build(split,count)
            hashes={prompt_hash(r['messages'][1]['content']) for r in rows}
            self.assertEqual(len(rows),count*20)
            self.assertEqual(len(hashes),len(rows))
            self.assertFalse(seen&hashes)
            seen.update(hashes)

    def test_date_and_reminder_labels_match_contract(self):
        for row in build('train',72):
            result=json.loads(row['messages'][-1]['content'])
            prompt=row['messages'][1]['content']
            kind=row['kind']
            if kind in (0,1):
                today=prompt.split('Today is ')[1].split('.')[0]
                self.assertEqual(datetime.fromisoformat(result['dueLocal']).date(),(datetime.fromisoformat(today)+timedelta(days=1)).date())
                self.assertNotIn('remindLocal',result)
            if kind==18:
                self.assertIn('remindLocal',result)
                self.assertNotIn('dueLocal',result)
            if kind==19:
                self.assertLess(result['remindLocal'],result['dueLocal'])
            if kind==2:
                self.assertLess(datetime.fromisoformat(result['startLocal']),datetime.fromisoformat(result['endLocal']))
            if kind in (3,4):self.assertEqual(result['action'],'clarify')


if __name__=='__main__':unittest.main()
