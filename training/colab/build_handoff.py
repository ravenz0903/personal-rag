"""Package a completed development adapter and data for a manual Colab transfer."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path
from prepare_data import MODEL,REVISION

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--run',required=True);parser.add_argument('--data',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();run=Path(args.run);data=Path(args.data);output=Path(args.output)
    if output.exists():raise ValueError('Existing transfer package is preserved')
    state=json.loads((run/'training-state.json').read_text());config=json.loads((run/'run-config.json').read_text());report=json.loads((run/'experiment-report.json').read_text())
    assert state['evaluation_complete'] and not state['deployed']
    assert config['model']==MODEL and config['revision']==REVISION and config['contract_version']==2
    assert report['model']==MODEL and report['revision']==REVISION and not report['deployed']
    manifest=json.loads((data/'manifest.json').read_text());assert manifest==config['data_manifest']
    adapter=json.loads((run/'adapter'/'adapter_config.json').read_text())
    assert adapter['peft_type']=='LORA' and adapter['r']==8 and set(adapter['target_modules'])=={'q_proj','v_proj'}
    files={}
    for name in ('run-config.json','experiment-report.json','training-state.json','adapter-validation.json','adapter-test.json'):
        files['imported/'+name]=(run/name).read_bytes()
    for name in ('adapter_config.json','adapter_model.safetensors','tokenizer.json','tokenizer_config.json','chat_template.jinja'):
        files['imported/adapter/'+name]=(run/'adapter'/name).read_bytes()
    for name in ('train.jsonl','validation.jsonl','test.jsonl','manifest.json'):
        raw=(data/name).read_bytes()
        if name.endswith('.jsonl'):assert hashlib.sha256(raw).hexdigest()==manifest['splits'][name.split('.')[0]]['sha256']
        files['imported/data/'+name]=raw
    files['TRANSFER.txt']=(f'Development adapter for {MODEL}, revision {REVISION}, contract v2.\nUpload manually through the continuation notebook. T4 uses FP16; evaluate on T4 before training. Import adapter weights with a new optimizer; do not claim exact checkpoint resume across different GPU/runtime versions.\nThe archive contains only tooling metadata, adapter weights and public/synthetic development data. It contains no Folio database, private notes, model base weights, credentials, or installation changes.\nThis package is NOT a release-qualified model and must not enable Folio AI automatically.\n').encode()
    inventory={'model':MODEL,'revision':REVISION,'contract_version':2,'release_qualified':False,'deployed':False,'transfer_type':'adapter weights; optimizer restarts','files':{name:{'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()} for name,raw in files.items()}}
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
        for name,raw in files.items():archive.writestr(name,raw)
        archive.writestr('transfer-manifest.json',json.dumps(inventory,indent=2))
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        for name,spec in inventory['files'].items():
            raw=archive.read(name);assert len(raw)==spec['bytes'] and hashlib.sha256(raw).hexdigest()==spec['sha256']
    print(json.dumps({'archive':str(output),'bytes':output.stat().st_size,'files':len(files),'verified':True,'release_qualified':False}))

if __name__=='__main__':main()
