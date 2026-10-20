"""Export only a qualified Qwen3 adapter, merging one original weight shard at a time.

Limited to explicitly supported rank-8 attention/full-linear LoRA profiles.
One weight delta and one shard at a time keep memory bounded. No training
or first-run downloads occur here; source, base, and adapter must already exist.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path
from colab.adapter_profiles import identify_profile, adapter_update_pairs, apply_adapter_update

ROOT = Path(__file__).resolve().parent.parent
REVISION = '1cfa9a7208912126459214e8b04321603b3df60c'

def digest(file):
    hash_ = hashlib.sha256()
    with file.open('rb') as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b''):
            hash_.update(chunk)
    return hash_.hexdigest()

def qualification_gate(adapter, prospective_report=None, prospective_fixtures=None):
    legacy = (adapter / 'pilot-report.json').exists()
    training_path = adapter / 'pilot-report.json' if legacy else adapter.parent / 'experiment-report.json'
    training = json.loads(training_path.read_text(encoding='utf-8'))
    if training['model'] != 'Qwen/Qwen3-4B' or training['revision'] != REVISION:
        raise ValueError('Export requires the pinned Qwen3-4B base')
    if not legacy:
        state=json.loads((adapter.parent/'training-state.json').read_text())
        if not state.get('evaluation_complete') or training.get('contract_version')!=2:
            raise ValueError('Contract-v2 training/evaluation is incomplete')
        raw=(adapter.parent/'train.jsonl').read_bytes()
        run_config=json.loads((adapter.parent/'run-config.json').read_text())
        if hashlib.sha256(raw).hexdigest()!=run_config['data_manifest']['splits']['train']['sha256']:
            raise ValueError('Training snapshot checksum mismatch')
        rows=[json.loads(x)['id'] for x in raw.decode().splitlines()]
        if len(rows)!=len(set(rows)) or training.get('training_rows')!=len(rows) or training.get('unique_optimized_rows')!=len(rows) or set(training['optimized_row_ids'])!=set(rows):
            raise ValueError('Full training-row coverage is required')
        if not prospective_report or not prospective_fixtures:
            raise ValueError('Contract-v2 export requires the prospective qualification report and frozen fixtures')
        for name in ('benchmark-report.json','fresh-report.json','qualification-report.json'):
            report=json.loads((adapter/name).read_text())
            if report.get('adapterSha256')!=digest(adapter/'adapter_model.safetensors') or report.get('trainingReportSha256')!=digest(training_path):
                raise ValueError('Evaluation report does not identify these trained weights: '+name)
        # Re-score raw outputs against the frozen references. Edited aggregate
        # pass counts are never sufficient to allow a merge/export.
        sys.path.insert(0,str(ROOT/'training/colab'))
        from evaluate_challenge import score
        frozen=Path(prospective_fixtures);raw=(frozen/'cases.jsonl').read_bytes()
        manifest=json.loads((frozen/'manifest.json').read_text())
        qualification_lock=json.loads((ROOT/'desktop/ai/release-qualification-lock.json').read_text())
        expected_fixture=qualification_lock['sha256']
        if len(raw)==0 or manifest.get('version')!=qualification_lock['version'] or manifest.get('rows')!=100 or manifest.get('sha256')!=expected_fixture or hashlib.sha256(raw).hexdigest()!=expected_fixture:
            raise ValueError('Prospective frozen qualification identity mismatch')
        fixtures=[json.loads(x) for x in raw.decode().splitlines()]
        snapshot=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in fixtures).encode()
        report=json.loads(Path(prospective_report).read_text())
        if report.get('model')!='Qwen/Qwen3-4B' or report.get('revision')!=REVISION or report.get('fixture_sha256')!=hashlib.sha256(snapshot).hexdigest() or report.get('adapterSha256')!=digest(adapter/'adapter_model.safetensors'):
            raise ValueError('Prospective report does not identify this model/fixture set')
        results=report['cases']['adapter']
        if len(fixtures)!=100 or len(results)!=100 or {r['id'] for r in results}!={r['id'] for r in fixtures} or report.get('capacity_exclusions'):
            raise ValueError('Prospective qualification must cover all 100 cases without exclusions')
        indexed={r['id']:r for r in results};judgments=[score(row,indexed[row['id']]['output']) for row in fixtures]
        if sum(r['passed'] for r in judgments)<95 or any(r['unsafe_output'] for r in judgments):
            raise ValueError('Prospective qualification failed: '+str(sum(r['passed'] for r in judgments))+'/100')
    # Re-score actual outputs, rather than trusting a manually edited pass count.
    script = """const fs=require('fs');for(const [file,module,count,min] of [
      ['benchmark-report.json','./desktop/ai/evaluation.cjs',12,12],
      ['fresh-report.json','./desktop/ai/holdout.cjs',16,16],
      ['qualification-report.json','./desktop/ai/qualification.cjs',100,95]]){
      const e=require(module),r=JSON.parse(fs.readFileSync(process.argv[1]+'/'+file,'utf8')),rows=r.cases.adapter;
      if(r.model!=='Qwen/Qwen3-4B'||r.revision!==process.argv[2]||rows.length!==count||new Set(rows.map(x=>x.id)).size!==count)throw Error('Incomplete or mismatched report '+file);
      let passed=0;for(const c of e.cases){const row=rows.find(x=>x.id===c.id);if(!row)throw Error('Missing case');let out;try{out=JSON.parse(row.output)}catch{}
      if(e.unsafe?.(c,out))throw Error('Unsafe qualification output');if(!e.score(c,out).length)passed++;}
      if(passed<min)throw Error('Quality gate failed: '+file+' '+passed+'/'+count);
      console.log(file+': '+passed+'/'+count);
    }"""
    subprocess.run(['node', '-e', script, str(adapter), REVISION], cwd=ROOT, check=True)
    return training

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adapter', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--source', default='target/llama-source-b11429')
    parser.add_argument('--prospective-report')
    parser.add_argument('--prospective-fixtures')
    args = parser.parse_args()
    adapter = Path(args.adapter).resolve(); output = Path(args.output).resolve(); source = Path(args.source).resolve()
    for folder in (adapter, output, source):
        if not folder.is_relative_to(ROOT / 'target'):
            raise ValueError('Model build inputs/outputs must be under ignored target/')
    training=qualification_gate(adapter,args.prospective_report,args.prospective_fixtures)
    if output.exists(): raise ValueError('Use a fresh export directory; preserve previous artifacts')
    config = json.loads((adapter / 'adapter_config.json').read_text(encoding='utf-8'))
    profile=identify_profile(config)
    source_commit = subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'], text=True).strip()
    runtime_commit = 'd81235049384534c167caea52b85a694f6103d14'
    if source_commit != runtime_commit: raise ValueError('Conversion source must match pinned runtime b11429')
    base = ROOT / 'target/ai-training-cache/hub/models--Qwen--Qwen3-4B/snapshots' / REVISION
    index = json.loads((base / 'model.safetensors.index.json').read_text(encoding='utf-8'))
    shards = sorted(set(index['weight_map'].values()))
    lock = json.loads((ROOT/'desktop/ai/base-lock.json').read_text(encoding='utf-8'))
    if lock['revision'] != REVISION or {f['path'] for f in lock['files']} != set(shards):
        raise ValueError('Original base identity mismatch')
    for file in lock['files']:
        original = base/file['path']
        if original.stat().st_size != file['bytes'] or digest(original) != file['sha256']:
            raise ValueError('Original publisher weight checksum mismatch')
    required = sum((base / name).stat().st_size for name in shards) * 2 + 4 * 1024**3
    if shutil.disk_usage(ROOT / 'target').free < required: raise RuntimeError('Not enough free space for merged, F16, and Q4 artifacts')
    import torch
    import psutil
    if psutil.virtual_memory().available < 6 * 1024**3:
        raise RuntimeError('Close other development processes before the bounded CPU merge; at least 6 GiB available RAM is required')
    from safetensors import safe_open
    from safetensors.torch import load_file, save_file
    weights = load_file(str(adapter / 'adapter_model.safetensors'), device='cpu')
    updates=adapter_update_pairs(weights,index['weight_map'],config)
    output.mkdir(); merged = output / 'merged'; merged.mkdir(); applied = set()
    for name in shards:
        tensors = {}
        with safe_open(str(base / name), framework='pt', device='cpu') as file:
            for key in file.keys():
                tensor = file.get_tensor(key).to(dtype=torch.float16, copy=True)
                if key in updates:
                    apply_adapter_update(tensor,updates[key],config['lora_alpha']/config['r'])
                    applied.add(key)
                tensors[key] = tensor
        save_file(tensors, str(merged / name), metadata={'format':'pt'})
        del tensors
        print(json.dumps({'merged_shard':name}), flush=True)
    if applied != set(updates): raise ValueError('Not every adapter delta was merged')
    for name in ['config.json','generation_config.json','model.safetensors.index.json']:
        shutil.copyfile(base / name, merged / name)
    for file in adapter.iterdir():
        if file.name.startswith('tokenizer') or file.name in ['special_tokens_map.json','chat_template.jinja','vocab.json','merges.txt']:
            shutil.copyfile(file, merged / file.name)
    f16 = output / 'folio-qwen3-4b-f16.gguf'; q4 = output / 'folio-qwen3-4b-q4_k_m.gguf'
    subprocess.run([sys.executable, str(source/'convert_hf_to_gguf.py'), str(merged), '--outfile',str(f16),'--outtype','f16'],check=True)
    quantizer = ROOT / 'target/ai-runtime/b11429/cpu/llama-quantize.exe'
    subprocess.run([str(quantizer),str(f16),str(q4),'Q4_K_M'],check=True)
    report = {'base':'Qwen/Qwen3-4B','baseRevision':REVISION,'adapterSha256':digest(adapter/'adapter_model.safetensors'),
              'runtime':'b11429','conversionCommit':source_commit,'adapterProfile':profile,'quantization':'Q4_K_M','bytes':q4.stat().st_size,
              'sha256':digest(q4),'exportedModelQualified':False,'deployed':False,
              'reports':{name:digest(adapter/name) for name in ['benchmark-report.json','fresh-report.json','qualification-report.json']}}
    if training.get('contract_version')==2:
        report.update({'contractVersion':2,'prospectiveQualificationVersion':json.loads((Path(args.prospective_fixtures)/'manifest.json').read_text())['version'],'prospectiveQualificationSha256':digest(Path(args.prospective_fixtures)/'cases.jsonl'),'prospectiveReportSha256':digest(Path(args.prospective_report))})
    (output/'export-manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report),flush=True)

if __name__ == '__main__': main()
