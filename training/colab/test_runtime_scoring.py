import json
import subprocess
import sys
import unittest
from pathlib import Path


class RuntimeScoringTests(unittest.TestCase):
    def test_truncation_cannot_count_as_a_successful_runtime_answer(self):
        root=Path(__file__).resolve().parents[2];fixtures=root/'target/folio-release-frozen-v3'
        if not fixtures.exists():self.skipTest('Generate prospective fixtures first')
        rows=[json.loads(x) for x in (fixtures/'cases.jsonl').read_text(encoding='utf-8').splitlines()]
        outputs=[{'id':r['id'],'output':r['messages'][-1]['content'],'finish_reason':'stop'} for r in rows]
        def judge():
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('score_frozen_outputs.py')),'--fixtures',str(fixtures)],input=json.dumps(outputs),text=True,capture_output=True,check=True,encoding='utf-8')
            return json.loads(result.stdout)
        complete=judge();self.assertEqual(complete['passed'],100);self.assertEqual(complete['unsafe'],0)
        for row in outputs[:6]:row['finish_reason']='length'
        truncated=judge();self.assertEqual(truncated['passed'],94)
        self.assertTrue(all('truncated or unfinished runtime output' in row['errors'] for row in truncated['cases'][:6]))


if __name__=='__main__':unittest.main()
