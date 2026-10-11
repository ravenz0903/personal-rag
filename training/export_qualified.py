"""Export only a qualified Qwen3 adapter, merging one original weight shard at a time.

Limited deliberately to the tested rank-8 q_proj/v_proj LoRA configuration. This
avoids loading all 4B parameters in RAM on the 16 GB target machine. No training
or first-run downloads occur here; source, base, and adapter must already exist.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REVISION = '1cfa9a7208912126459214e8b04321603b3df60c'

def digest(file):
    hash_ = hashlib.sha256()
    with file.open('rb') as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b''):
            hash_.update(chunk)
    return hash_.hexdigest()

def qualification_gate(adapter):
    training = json.loads((adapter / 'pilot-report.json').read_text(encoding='utf-8'))
    if training['model'] != 'Qwen/Qwen3-4B' or training['revision'] != REVISION:
        raise ValueError('Export requires the pinned Qwen3-4B base')
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
    args = parser.parse_args()
    adapter = Path(args.adapter).resolve(); output = Path(args.output).resolve(); source = Path(args.source).resolve()
    for folder in (adapter, output, source):
        if not folder.is_relative_to(ROOT / 'target'):
            raise ValueError('Model build inputs/outputs must be under ignored target/')
    qualification_gate(adapter)
    if output.exists(): raise ValueError('Use a fresh export directory; preserve previous artifacts')
    config = json.loads((adapter / 'adapter_config.json').read_text(encoding='utf-8'))
    if config.get('peft_type') != 'LORA' or set(config.get('target_modules', [])) != {'q_proj','v_proj'} or config.get('r') != 8 or config.get('lora_alpha') != 16 or config.get('bias') != 'none' or any(config.get(k) for k in ['use_dora','use_rslora','modules_to_save','rank_pattern','alpha_pattern']):
        raise ValueError('Unsupported adapter configuration; do not silently mismerge')
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
    updates = {}
    for key, value in weights.items():
        if not key.endswith('.lora_A.weight'): continue
        bkey = key.replace('.lora_A.weight','.lora_B.weight')
        if bkey not in weights or not key.startswith('base_model.model.'): raise ValueError('Unexpected adapter weight names')
        basekey = key[len('base_model.model.'):].replace('.lora_A.weight','.weight')
        if basekey not in index['weight_map']: raise ValueError('Adapter target absent from original base')
        # Standard PEFT CPU FP16 LoRA merge: FP32 matmul, then convert delta to weight dtype.
        updates[basekey] = (weights[bkey].float() @ value.float() * (config['lora_alpha']/config['r'])).to(torch.float16)
    if len(weights) != 2 * len(updates): raise ValueError('Unrecognized adapter tensors')
    output.mkdir(); merged = output / 'merged'; merged.mkdir(); applied = set()
    for name in shards:
        tensors = {}
        with safe_open(str(base / name), framework='pt', device='cpu') as file:
            for key in file.keys():
                tensor = file.get_tensor(key).to(dtype=torch.float16, copy=True)
                if key in updates:
                    if tensor.shape != updates[key].shape: raise ValueError('Adapter shape mismatch')
                    tensor.add_(updates[key]); applied.add(key)
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
              'runtime':'b11429','conversionCommit':source_commit,'quantization':'Q4_K_M','bytes':q4.stat().st_size,
              'sha256':digest(q4),'exportedModelQualified':False,'deployed':False,
              'reports':{name:digest(adapter/name) for name in ['benchmark-report.json','fresh-report.json','qualification-report.json']}}
    (output/'export-manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report),flush=True)

if __name__ == '__main__': main()
