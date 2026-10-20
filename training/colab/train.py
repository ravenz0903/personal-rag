"""Checkpointed QLoRA experiment for local CUDA or Colab. Never deploys."""
import argparse
import contextlib
import hashlib
import json
import os
import random
import shutil
import time
from pathlib import Path
from prepare_data import MODEL, REVISION, validate
from adapter_profiles import PROFILES, identify_profile, inherit_attention_weights

def write_json(path, value):
    path = Path(path)
    temporary = path.with_suffix(path.suffix+'.tmp')
    temporary.write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8')
    temporary.replace(path)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data',required=True); parser.add_argument('--output',required=True)
    parser.add_argument('--base-path'); parser.add_argument('--max-length',type=int,default=1024)
    parser.add_argument('--steps',type=int,default=300); parser.add_argument('--accumulation',type=int,default=4)
    parser.add_argument('--eval-per-kind',type=int,default=1); parser.add_argument('--checkpoint-every',type=int,default=25)
    parser.add_argument('--learning-rate',type=float,default=5e-5)
    parser.add_argument('--resume-checkpoint'); parser.add_argument('--evaluate-only',action='store_true')
    parser.add_argument('--parent-adapter',help='Start a new, explicitly recorded dataset experiment from adapter weights')
    parser.add_argument('--baseline-from',help='Reuse an identical base evaluation from a previous run')
    parser.add_argument('--adapter-profile',choices=PROFILES,default='attention')
    args = parser.parse_args()
    assert 1<=args.steps<=2000 and 1<=args.accumulation<=32 and 256<=args.max_length<=2048
    assert args.checkpoint_every>0 and args.eval_per_kind>0 and 0<args.learning_rate<=1e-3
    assert not (args.parent_adapter and args.resume_checkpoint), 'Choose transfer or exact checkpoint resume'
    os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY','1')
    if args.base_path:
        os.environ['HF_HUB_OFFLINE']='1'
    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
    from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training, PeftModel
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA GPU required. Choose T4 in Colab; CPU fallback is disabled.')
    random.seed(42); torch.manual_seed(42); torch.cuda.manual_seed_all(42)
    output = Path(args.output).resolve(); data = Path(args.data).resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError('Use a new output folder; old experiments are preserved')
    manifest = json.loads((data/'manifest.json').read_text(encoding='utf-8'))
    assert manifest['model']==MODEL and manifest['revision']==REVISION and manifest['contract_version']==2
    splits = {}
    for split in ('train','validation','test'):
        raw=(data/(split+'.jsonl')).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256'], 'Data checksum mismatch'
        splits[split]=[json.loads(line) for line in raw.decode('utf-8').splitlines()]
        for row in splits[split]: validate(row)
    base = args.base_path or MODEL
    dtype = torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    print(json.dumps({'stage':'hardware','gpu':torch.cuda.get_device_name(0),'total_vram':torch.cuda.get_device_properties(0).total_memory,'dtype':str(dtype),'torch':torch.__version__}),flush=True)
    tokenizer = AutoTokenizer.from_pretrained(base,revision=None if args.base_path else REVISION,trust_remote_code=False,local_files_only=bool(args.base_path))
    tokenizer.pad_token=tokenizer.eos_token
    def encode(row):
        prompt=tokenizer.apply_chat_template(row['messages'][:-1],tokenize=False,add_generation_prompt=True,enable_thinking=False)
        prefix=tokenizer(prompt,add_special_tokens=False)['input_ids']
        answer=tokenizer(row['messages'][-1]['content']+tokenizer.eos_token,add_special_tokens=False)['input_ids']
        ids=prefix+answer
        if len(ids)>args.max_length: raise ValueError('Example exceeds context budget; never truncate evidence/labels: '+row['id'])
        return {'input_ids':ids,'labels':[-100]*len(prefix)+answer,'prefix':prefix}
    encoded={split:[encode(r) for r in rows] for split,rows in splits.items()}
    lengths=[len(x['input_ids']) for rows in encoded.values() for x in rows]
    output.mkdir(parents=True)
    for filename in ('train.jsonl','validation.jsonl','test.jsonl','manifest.json'):
        shutil.copyfile(data/filename,output/filename)
    write_json(output/'run-config.json',{'model':MODEL,'revision':REVISION,'contract_version':2,'arguments':vars(args),'data_manifest':manifest,'gpu':torch.cuda.get_device_name(0),'precision':str(dtype),'max_example_tokens':max(lengths),'truncated_rows':0,'deployment_enabled':False})
    print(json.dumps({'stage':'preflight','rows':{k:len(v) for k,v in splits.items()},'max_tokens':max(lengths),'truncated':0}),flush=True)
    model=AutoModelForCausalLM.from_pretrained(base,revision=None if args.base_path else REVISION,trust_remote_code=False,local_files_only=bool(args.base_path),device_map={'':0},quantization_config=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=dtype),dtype=dtype,attn_implementation='sdpa')
    def batch(x):
        return {'input_ids':torch.tensor([x['input_ids']],device='cuda'),'labels':torch.tensor([x['labels']],device='cuda'),'attention_mask':torch.ones((1,len(x['input_ids'])),device='cuda',dtype=torch.long)}
    def select(split):
        counts={}; selected=[]
        for i,row in enumerate(splits[split]):
            count=counts.get(row['kind'],0)
            if count<args.eval_per_kind: selected.append(i); counts[row['kind']]=count+1
        return selected
    def evaluate(label,split='validation'):
        model.eval(); results=[]
        with torch.inference_mode():
            for i in select(split):
                row=splits[split][i]; prefix=encoded[split][i]['prefix']
                ids=torch.tensor([prefix],device='cuda')
                generated=model.generate(input_ids=ids,attention_mask=torch.ones_like(ids),max_new_tokens=256,do_sample=False,pad_token_id=tokenizer.eos_token_id,use_cache=True)
                raw=tokenizer.decode(generated[0,len(prefix):],skip_special_tokens=True)
                expected=json.loads(row['messages'][-1]['content']); valid=False; match=False; errors=[]
                try:
                    actual=json.loads(raw); candidate={**row,'messages':row['messages'][:-1]+[{'role':'assistant','content':raw}]}; validate(candidate);valid=True
                    match=set(actual)==set(expected) and all(actual.get(k)==v for k,v in expected.items() if k!='answer')
                    if not match: errors.append('Structured fields differ from expected')
                except (ValueError,TypeError,AssertionError,KeyError) as error: errors.append(str(error) or type(error).__name__)
                result={'id':row['id'],'kind':row['kind'],'prompt':row['messages'][1]['content'],'expected':expected,'output':raw,'schema_valid':valid,'structured_fields_match':valid and match,'manual_text_review_required':row['kind'] in ('grounded','summary','injection','abstain'),'errors':errors}
                results.append(result)
                print(json.dumps({'stage':label,'id':row['id'],'valid':valid,'structured_match':valid and match}),flush=True)
        report={'label':label,'split':split,'model':MODEL,'revision':REVISION,'cases':results,'count':len(results),'valid':sum(r['schema_valid'] for r in results),'structured_matches':sum(r['structured_fields_match'] for r in results),'factual_accuracy_measured':False,'release_qualified':False}
        write_json(output/(label+'.json'),report)
        return report
    if args.baseline_from:
        previous=Path(args.baseline_from)
        config=json.loads((previous/'run-config.json').read_text(encoding='utf-8'))
        assert config['model']==MODEL and config['revision']==REVISION and config['contract_version']==2
        assert config['precision']==str(dtype) and config['gpu']==torch.cuda.get_device_name(0)
        assert config['arguments']['eval_per_kind']==args.eval_per_kind
        assert config['data_manifest']['contract_sha256']==manifest['contract_sha256']
        for split in ('validation','test'):
            assert config['data_manifest']['splits'][split]['sha256']==manifest['splits'][split]['sha256'], 'Baseline evaluation data differs'
        reports=[]
        for label,split in [('base-validation','validation'),('base-test','test')]:
            report=json.loads((previous/(label+'.json')).read_text(encoding='utf-8'))
            assert report['model']==MODEL and report['revision']==REVISION
            assert [case['id'] for case in report['cases']]==[splits[split][i]['id'] for i in select(split)]
            reports.append(report);write_json(output/(label+'.json'),report)
        baseline,baseline_test=reports
        print(json.dumps({'stage':'baseline-reused','source':str(previous),'identical_evaluation_data':True}),flush=True)
    else:
        baseline=evaluate('base-validation')
        baseline_test=evaluate('base-test','test')
    model=prepare_model_for_kbit_training(model,use_gradient_checkpointing=True)
    if args.resume_checkpoint:
        checkpoint=Path(args.resume_checkpoint)
        saved=json.loads((checkpoint/'checkpoint-state.json').read_text())
        assert saved['model']==MODEL and saved['revision']==REVISION and saved['data_manifest']==manifest and saved['accumulation']==args.accumulation
        model=PeftModel.from_pretrained(model,checkpoint,is_trainable=True)
    elif args.parent_adapter:
        parent=Path(args.parent_adapter)
        parent_config=json.loads((parent.parent/'run-config.json').read_text(encoding='utf-8'))
        assert parent_config['model']==MODEL and parent_config['revision']==REVISION and parent_config['contract_version']==2
        assert parent_config['data_manifest']['contract_sha256']==manifest['contract_sha256']
        config=json.loads((parent/'adapter_config.json').read_text(encoding='utf-8'))
        parent_profile=identify_profile(config)
        if parent_profile==args.adapter_profile:
            model=PeftModel.from_pretrained(model,parent,is_trainable=True)
            transfer={'inherited_profile':parent_profile}
        elif parent_profile=='attention' and args.adapter_profile=='full-linear':
            from safetensors.torch import load_file
            model=get_peft_model(model,LoraConfig(r=8,lora_alpha=16,lora_dropout=0.05,target_modules=list(PROFILES['full-linear']),bias='none',task_type='CAUSAL_LM'))
            transfer=inherit_attention_weights(model,load_file(str(parent/'adapter_model.safetensors'),device='cpu'))
        else:
            raise ValueError('Unsupported adapter profile transfer')
        print(json.dumps({'stage':'transfer','parent':str(parent),'optimizer_restarted':True,'adapter_profile':args.adapter_profile,**transfer}),flush=True)
    else:
        model=get_peft_model(model,LoraConfig(r=8,lora_alpha=16,lora_dropout=0.05,target_modules=list(PROFILES[args.adapter_profile]),bias='none',task_type='CAUSAL_LM'))
    actual_profile=identify_profile(model.peft_config['default'].to_dict())
    assert actual_profile==args.adapter_profile,'Requested and loaded adapter profiles differ'
    print(json.dumps({'stage':'adapter-capacity','profile':actual_profile,'trainable_parameters':sum(p.numel() for p in model.parameters() if p.requires_grad)}),flush=True)
    model.config.use_cache=False
    optimizer=torch.optim.AdamW((p for p in model.parameters() if p.requires_grad),lr=args.learning_rate)
    scaler=torch.amp.GradScaler('cuda',enabled=dtype==torch.float16)
    first_step=0; history=[]; order=list(range(len(splits['train'])))
    random.Random(42).shuffle(order)
    if args.resume_checkpoint:
        state=torch.load(checkpoint/'optimizer-state.pt',map_location='cpu',weights_only=True)
        optimizer.load_state_dict(state['optimizer']);scaler.load_state_dict(state['scaler']);torch.set_rng_state(state['torch_rng']);torch.cuda.set_rng_state(state['cuda_rng'])
        first_step=saved['steps_completed'];history=saved['losses']
    def checkpoint_at(step):
        folder=output/f'checkpoint-{step:04d}'
        folder.mkdir(exist_ok=False)
        model.save_pretrained(folder,safe_serialization=True);tokenizer.save_pretrained(folder)
        torch.save({'optimizer':optimizer.state_dict(),'scaler':scaler.state_dict(),'torch_rng':torch.get_rng_state(),'cuda_rng':torch.cuda.get_rng_state()},folder/'optimizer-state.pt')
        write_json(folder/'checkpoint-state.json',{'model':MODEL,'revision':REVISION,'data_manifest':manifest,'accumulation':args.accumulation,'steps_completed':step,'losses':history})
        write_json(output/'training-state.json',{'steps_completed':step,'checkpoint':str(folder),'evaluation_complete':False,'deployed':False})
    completed=first_step;start=time.time();torch.cuda.reset_peak_memory_stats()
    try:
        if not args.evaluate_only:
            model.train();optimizer.zero_grad(set_to_none=True)
            for step in range(first_step,args.steps):
                total=0.0
                for micro in range(args.accumulation):
                    position=(step*args.accumulation+micro)%len(order)
                    with torch.autocast('cuda',dtype=dtype):
                        loss=model(**batch(encoded['train'][order[position]])).loss
                    if not torch.isfinite(loss): raise RuntimeError('Non-finite training loss')
                    total+=float(loss.detach())/args.accumulation
                    scaler.scale(loss/args.accumulation).backward()
                scaler.unscale_(optimizer);torch.nn.utils.clip_grad_norm_(model.parameters(),1.0)
                scaler.step(optimizer);scaler.update();optimizer.zero_grad(set_to_none=True)
                completed=step+1;history.append(total)
                print(json.dumps({'stage':'train','step':completed,'steps':args.steps,'loss':total,'seconds':round(time.time()-start,1),'peak_vram_gb':round(torch.cuda.max_memory_allocated()/1024**3,2)}),flush=True)
                if completed%args.checkpoint_every==0: checkpoint_at(completed)
        model.save_pretrained(output/'adapter',safe_serialization=True);tokenizer.save_pretrained(output/'adapter')
        tuned=evaluate('adapter-validation')
        # Test is read only after training, never used for updates or checkpoint selection.
        test=evaluate('adapter-test','test')
        report={'model':MODEL,'revision':REVISION,'contract_version':2,'steps':completed,'optimized_row_ids':[splits['train'][order[i%len(order)]]['id'] for i in range(completed*args.accumulation)],'training_rows':len(order),'unique_optimized_rows':min(completed*args.accumulation,len(order)),'gradient_accumulation':args.accumulation,'losses':history,'seconds':time.time()-start,'peak_vram_bytes':torch.cuda.max_memory_allocated(),'base_validation':baseline['structured_matches'],'adapter_validation':tuned['structured_matches'],'validation_count':tuned['count'],'base_test':baseline_test['structured_matches'],'adapter_test':test['structured_matches'],'test_count':test['count'],'factual_accuracy_measured':False,'synthetic_smoke_only':manifest['synthetic_smoke_only'],'release_qualified':False,'deployed':False}
        write_json(output/'experiment-report.json',report)
        write_json(output/'training-state.json',{'steps_completed':completed,'evaluation_complete':True,'deployed':False})
        print(json.dumps({'stage':'complete','report_path':str(output/'experiment-report.json'),'summary':{k:v for k,v in report.items() if k not in ('optimized_row_ids','losses')}}),flush=True)
    except BaseException as error:
        write_json(output/'failure.json',{'error':type(error).__name__,'message':str(error),'steps_completed':completed,'deployed':False})
        # Preserve the last fully completed optimizer step, discard partial gradients.
        optimizer.zero_grad(set_to_none=True)
        if completed>first_step and not (output/f'checkpoint-{completed:04d}').exists():checkpoint_at(completed)
        raise

if __name__=='__main__': main()
