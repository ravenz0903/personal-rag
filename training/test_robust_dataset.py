import json
import subprocess
import unittest
from pathlib import Path
from build_robust_dataset import build


class RobustDatasetIntegrity(unittest.TestCase):
    def test_splits_and_fresh_probes_are_separate(self):
        train, validation = build('train',32), build('validation',2)
        prompts=lambda rows:{x['messages'][1]['content'] for x in rows}
        self.assertEqual(len(prompts(train)),len(train))
        self.assertFalse(prompts(train)&prompts(validation))
        root=Path(__file__).resolve().parent.parent
        probes=json.loads(subprocess.check_output(['node','-e',"process.stdout.write(JSON.stringify(require('./desktop/ai/holdout.cjs').cases.map(c=>c.prompt)))"],cwd=root,text=True,encoding='utf-8'))
        self.assertFalse((prompts(train)|prompts(validation))&set(probes))
        self.assertEqual({x['kind'] for x in validation[:16]},set(range(16)))

    def test_varied_times_and_reminders_are_consistent(self):
        from datetime import datetime,timedelta
        rows=build('train',32)
        event_starts=set()
        for row in rows:
            proposal=json.loads(row['messages'][-1]['content'])
            prompt=row['messages'][1]['content']
            if row['kind']==2:
                self.assertLess(proposal['startLocal'],proposal['endLocal'])
                self.assertIn(proposal['startLocal'],prompt)
                self.assertIn(proposal['endLocal'],prompt)
                event_starts.add(proposal['startLocal'][11:])
            if row['kind']==1:
                current=prompt.split('Current local date: ')[1].split('.')[0]
                self.assertEqual(datetime.fromisoformat(proposal['dueLocal']).date(),(datetime.fromisoformat(current)+timedelta(days=1)).date())
            if row['kind'] in (8,13):
                self.assertEqual(proposal['action'],'answer')
                self.assertFalse(proposal['abstain'])
        self.assertGreater(len(event_starts),8)


if __name__=='__main__':unittest.main()
