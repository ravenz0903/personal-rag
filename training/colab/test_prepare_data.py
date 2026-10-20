import copy
import json
import hashlib
import tempfile
import unittest
from pathlib import Path
from prepare_data import build, validate, write_dataset

class DataChecks(unittest.TestCase):
    def test_split_generation_and_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            manifest=write_dataset(Path(folder)/'data')
            self.assertEqual(manifest['splits']['train']['rows'],1200)
            self.assertEqual(manifest['splits']['test']['rows'],120)
            self.assertTrue(manifest['synthetic_smoke_only'])
            for split in ('train','validation','test'):
                self.assertEqual(hashlib.sha256((Path(folder)/'data'/(split+'.jsonl')).read_bytes()).hexdigest(),manifest['splits'][split]['sha256'])

    def altered(self,kind,change):
        row=next(r for r in build('train',1) if r['kind']==kind)
        proposal=json.loads(row['messages'][-1]['content']);change(proposal)
        row['messages'][-1]['content']=json.dumps(proposal)
        return row

    def test_invented_citation_rejected(self):
        with self.assertRaises(AssertionError):validate(self.altered('grounded',lambda p:p.update(citations=['invented'])))

    def test_deadline_only_reminder_rejected(self):
        def change(p):p['dueLocal']=p.pop('remindLocal')
        with self.assertRaises(AssertionError):validate(self.altered('reminder',change))

    def test_personal_write_in_all_rejected(self):
        row=next(r for r in build('train',3) if r['kind']=='task' and r['scope']=='PERSONAL');row['scope']='ALL'
        with self.assertRaises(AssertionError):validate(row)

    def test_invalid_calendar_date_rejected(self):
        with self.assertRaises(ValueError):validate(self.altered('deadline',lambda p:p.update(dueLocal='2026-04-31T08:00')))

    def test_existing_data_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'data';write_dataset(path)
            before=(path/'manifest.json').read_bytes()
            with self.assertRaises(ValueError):write_dataset(path)
            self.assertEqual(before,(path/'manifest.json').read_bytes())

if __name__=='__main__':unittest.main()
