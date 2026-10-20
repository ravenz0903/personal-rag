"""Security/quality regressions: aggregate counts cannot authorize export."""
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from export_qualified import qualification_gate, REVISION, ROOT


class ExportGateTests(unittest.TestCase):
    def fixture(self, root):
        run=Path(root);adapter=run/'adapter';adapter.mkdir()
        (adapter/'adapter_model.safetensors').write_bytes(b'fake weights; never loaded')
        raw=b'{"id":"training-example"}\n';(run/'train.jsonl').write_bytes(raw)
        report={'model':'Qwen/Qwen3-4B','revision':REVISION,'contract_version':2,'training_rows':1,'unique_optimized_rows':1,'optimized_row_ids':['training-example']}
        (run/'experiment-report.json').write_text(json.dumps(report))
        (run/'training-state.json').write_text(json.dumps({'evaluation_complete':True}))
        (run/'run-config.json').write_text(json.dumps({'data_manifest':{'splits':{'train':{'sha256':hashlib.sha256(raw).hexdigest()}}}}))
        for name in ('benchmark-report.json','fresh-report.json','qualification-report.json'):
            (adapter/name).write_text(json.dumps({'passed':{'adapter':100},'adapterSha256':hashlib.sha256((adapter/'adapter_model.safetensors').read_bytes()).hexdigest(),'trainingReportSha256':hashlib.sha256((run/'experiment-report.json').read_bytes()).hexdigest()}))
        return adapter

    def test_changed_weights_cannot_reuse_good_report_counts(self):
        with tempfile.TemporaryDirectory() as root:
            adapter=self.fixture(root)
            (adapter/'adapter_model.safetensors').write_bytes(b'different weights')
            with self.assertRaisesRegex(ValueError,'does not identify these trained weights'):
                qualification_gate(adapter,'unused-report','unused-fixtures')

    def test_changed_training_snapshot_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            adapter=self.fixture(root)
            (adapter.parent/'train.jsonl').write_bytes(b'{"id":"replacement"}\n')
            with self.assertRaisesRegex(ValueError,'snapshot checksum'):
                qualification_gate(adapter,'unused-report','unused-fixtures')

    def test_forged_prospective_count_is_rescored(self):
        lock=json.loads((ROOT/'desktop/ai/release-qualification-lock.json').read_text())
        frozen=ROOT/lock['folder']
        if not frozen.exists():self.skipTest('Generate prospective fixtures before export checks')
        rows=[json.loads(x) for x in (frozen/'cases.jsonl').read_text(encoding='utf-8').splitlines()]
        snapshot=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode()
        with tempfile.TemporaryDirectory() as root:
            adapter=self.fixture(root);report=Path(root)/'prospective.json'
            report.write_text(json.dumps({'model':'Qwen/Qwen3-4B','revision':REVISION,'fixture_sha256':hashlib.sha256(snapshot).hexdigest(),'adapterSha256':hashlib.sha256((adapter/'adapter_model.safetensors').read_bytes()).hexdigest(),'adapter_passed':100,'capacity_exclusions':[],'cases':{'adapter':[{'id':r['id'],'output':'{"action":"create_task","area":"PERSONAL","title":"Injected write"}'} for r in rows]}}))
            with self.assertRaisesRegex(ValueError,'Prospective qualification failed'):
                qualification_gate(adapter,report,frozen)


if __name__=='__main__':unittest.main()
