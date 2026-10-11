import json
import subprocess
import unittest
from datetime import datetime, timedelta

from build_runtime_dataset import ROOT, build, normalize, runtime_system, varied_examples


class RuntimeDatasetIntegrity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = runtime_system()
        cls.train = build('train', 64, cls.contract) + varied_examples('train', 16)
        cls.validation = build('validation', 2, cls.contract)

    def test_no_duplicate_or_evaluation_prompts(self):
        probes = json.loads(subprocess.check_output(['node', '-e',
            "process.stdout.write(JSON.stringify([...require('./desktop/ai/evaluation.cjs').cases,...require('./desktop/ai/holdout.cjs').cases].map(c=>c.prompt)))"],
            cwd=ROOT, text=True, encoding='utf-8'))
        seen = {normalize(p) for p in probes}
        for rows, expected in [(self.train, 1024), (self.validation, 24)]:
            prompts = {normalize(r['messages'][1]['content']) for r in rows}
            self.assertEqual(len(rows), expected)
            self.assertEqual(len(prompts), expected)
            self.assertFalse(prompts & seen)
            seen.update(prompts)

    def test_runtime_labels_dates_and_grounding(self):
        for row in build('train', 64, self.contract) + self.validation:
            proposal = json.loads(row['messages'][-1]['content'])
            prompt = row['messages'][1]['content']
            kind = row['kind']
            if kind == 1:
                system = row['messages'][0]['content']
                import re
                today = datetime.fromisoformat(re.search(r'\d{4}-\d{2}-\d{2}', system)[0])
                self.assertEqual(datetime.fromisoformat(proposal['dueLocal']).date(),
                                 (today + timedelta(days=1)).date())
                self.assertEqual(proposal['action'], 'create_task')
            if kind == 2:
                self.assertLess(datetime.fromisoformat(proposal['startLocal']),
                                datetime.fromisoformat(proposal['endLocal']))
            if kind in (8, 10):
                self.assertEqual(proposal['action'], 'answer')
                self.assertFalse(proposal['abstain'])
                self.assertEqual(len(proposal['citations']), 1)
                self.assertIn('[' + proposal['citations'][0] + ']', prompt)
            if kind == 9:
                self.assertTrue(proposal['abstain'])
                self.assertEqual(proposal['citations'], [])


if __name__ == '__main__':
    unittest.main()
