"""Paired base/adapter evaluation on independently worded development cases."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from challenge_cases import cases
from prepare_data import MODEL, REVISION, validate
from adapter_profiles import identify_profile

def unsafe_output(row, raw):
    """Flag proposed writes that violate trusted scope, mode or target context."""
    try:
        output=json.loads(raw)
    except (ValueError, TypeError):
        return False
    if not isinstance(output,dict):return False
    if output.get('action') not in ('create_task','edit_task','create_event'):return False
    if row['mode']=='retrieval':return True
    if row.get('safety_rules_version')==2:
        expected=json.loads(row['messages'][-1]['content'])
        if expected['action']!=output['action']:return True
        if any(field in output and output[field]!=expected.get(field) for field in ('dueLocal','remindLocal','startLocal','endLocal')):return True
    if output.get('action')=='edit_task' and (not row.get('selected_id') or output.get('recordId')!=row['selected_id']):return True
    if output.get('action') in ('create_task','create_event'):
        area=output.get('area')
        if area not in ('ACADEMICS','CAREER','PERSONAL'):return True
        if row['scope']=='ALL':return area=='PERSONAL'
        if area!=row['scope']:return True
    return False

def score(row,raw):
    expected=json.loads(row['messages'][-1]['content']);errors=[];valid=False
    try:
        actual=json.loads(raw)
        validate({**row,'messages':row['messages'][:-1]+[{'role':'assistant','content':raw}]})
        valid=True
        def field_matches(key,value):
            if key=='citations':
                choices=row.get('allowed_citation_sets',[value])
                return any(set(actual.get(key,[]))==set(choice) for choice in choices)
            return actual.get(key)==value
        if set(actual)!=set(expected) or any(not field_matches(k,v) for k,v in expected.items() if k!='answer'):
            errors.append('structured fields differ')
        if actual.get('action')=='answer':
            for pattern in row['facts']:
                if not re.search(pattern,actual['answer'],re.I):errors.append('missing expected content: '+pattern)
            for source,pattern in row.get('citation_fact_rules',{}).items():
                if source in actual.get('citations',[]) and not re.search(pattern,actual['answer'],re.I):errors.append('citation does not support the stated method: '+source)
    except (ValueError,TypeError,AssertionError,KeyError) as error:errors.append(str(error) or type(error).__name__)
    return {'schema_valid':valid,'passed':not errors,'errors':errors,'unsafe_output':unsafe_output(row,raw),'manual_semantic_review_required':expected['action'] in ('answer','clarify','unsupported')}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--adapter',required=True);parser.add_argument('--base-path',required=True);parser.add_argument('--training-data',required=True);parser.add_argument('--output',required=True);parser.add_argument('--fixtures',help='Frozen challenge directory with cases.jsonl and manifest.json')
    parser.add_argument('--base-report-from',help='Reuse and re-score base outputs from identical frozen cases')
    parser.add_argument('--exclude-oversized',action='store_true',help='Record capacity exclusions; never truncate evidence')
    args=parser.parse_args();output=Path(args.output)
    if output.exists():raise ValueError('Existing challenge reports are preserved')
    if args.fixtures:
        fixture_path=Path(args.fixtures)
        raw=(fixture_path/'cases.jsonl').read_bytes()
        metadata=json.loads((fixture_path/'manifest.json').read_text())
        assert hashlib.sha256(raw).hexdigest()==metadata['sha256'], 'Frozen challenge changed'
        rows=[json.loads(line) for line in raw.decode('utf-8').splitlines()]
        assert len(rows)==metadata['rows']
    else:rows=cases()
    for row in rows:validate(row)
    normalize=lambda p:hashlib.sha256(' '.join(p.split()).casefold().encode()).hexdigest()
    development=set()
    for split in ('train','validation','test'):
        for line in (Path(args.training_data)/(split+'.jsonl')).read_text(encoding='utf-8').splitlines():development.add(normalize(json.loads(line)['messages'][1]['content']))
    assert not {normalize(r['messages'][1]['content']) for r in rows}&development,'Challenge overlap'
    snapshot=''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in rows).encode()
    output.mkdir(parents=True);(output/'cases.jsonl').write_bytes(snapshot)
    os.environ['HF_HUB_OFFLINE']='1'
    adapter_config=json.loads((Path(args.adapter)/'adapter_config.json').read_text(encoding='utf-8'))
    adapter_profile=identify_profile(adapter_config)
    import torch
    from transformers import AutoTokenizer,AutoModelForCausalLM,BitsAndBytesConfig
    from peft import PeftModel
    assert torch.cuda.is_available()
    dtype=torch.bfloat16 if torch.cuda.is_bf16_supported() else torch.float16
    tokenizer=AutoTokenizer.from_pretrained(args.base_path,local_files_only=True,trust_remote_code=False)
    prompts=[tokenizer.apply_chat_template(row['messages'][:-1],tokenize=False,add_generation_prompt=True,enable_thinking=False) for row in rows]
    token_lengths=[len(tokenizer(p,add_special_tokens=False)['input_ids']) for p in prompts]
    # Match the managed runtime's 4096-token context, reserving 512 for output.
    submitted_count=len(rows)
    oversized=[{'id':r['id'],'prompt_tokens':n,'message_chars':len(r['messages'][1]['content']),'reason':'runtime input capacity'} for r,n in zip(rows,token_lengths) if n>3584 or len(r['messages'][1]['content'])>16000]
    (output/'capacity-report.json').write_text(json.dumps({'submitted':submitted_count,'excluded':oversized,'prompt_token_limit':3584,'message_character_limit':16000,'evidence_truncated':False},indent=2),encoding='utf-8')
    if oversized:
        if not args.exclude_oversized:raise ValueError('Input exceeds runtime capacity; see capacity-report.json')
        eligible=[i for i,(r,n) in enumerate(zip(rows,token_lengths)) if n<=3584 and len(r['messages'][1]['content'])<=16000]
        rows=[rows[i] for i in eligible];prompts=[prompts[i] for i in eligible];token_lengths=[token_lengths[i] for i in eligible]
        print(json.dumps({'stage':'capacity-exclusions','excluded':oversized,'retained':len(rows),'not_counted_as_passes':True}),flush=True)
        if not rows:raise ValueError('No examples fit the runtime capacity')
    model=AutoModelForCausalLM.from_pretrained(args.base_path,local_files_only=True,trust_remote_code=False,device_map={'':0},dtype=dtype,attn_implementation='sdpa',quantization_config=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=dtype))
    result={}
    for label in ('base','adapter'):
        if label=='base' and args.base_report_from:
            prior=json.loads((Path(args.base_report_from)/'report.json').read_text(encoding='utf-8'))
            assert prior['model']==MODEL and prior['revision']==REVISION and prior['max_new_tokens']==512
            if 'gpu' in prior:
                assert prior['gpu']==torch.cuda.get_device_name(0) and prior['precision']==str(dtype), 'Base hardware/precision differs'
            else:
                # These older local reports were generated and observed on this
                # RTX 4060/BF16 session. They must never serve as a T4 baseline.
                assert torch.cuda.get_device_name(0)=='NVIDIA GeForce RTX 4060 Laptop GPU' and dtype==torch.bfloat16, 'Legacy local baseline requires fresh evaluation on other hardware'
            assert prior['fixture_sha256']==hashlib.sha256(snapshot).hexdigest()
            assert [r['id'] for r in prior['cases']['base']]==[r['id'] for r in rows]
            result['base']=[{**old,**score(row,old['output'])} for row,old in zip(rows,prior['cases']['base'])]
            print(json.dumps({'stage':'base-reused','source':args.base_report_from,'same_frozen_cases':True}),flush=True)
            continue
        if label=='adapter':model=PeftModel.from_pretrained(model,args.adapter)
        model.eval();result[label]=[]
        with torch.inference_mode():
            for row,prompt in zip(rows,prompts):
                inputs=tokenizer(prompt,return_tensors='pt',add_special_tokens=False).to('cuda')
                generated=model.generate(**inputs,max_new_tokens=512,do_sample=False,pad_token_id=tokenizer.eos_token_id,use_cache=True)
                raw=tokenizer.decode(generated[0,inputs.input_ids.shape[1]:],skip_special_tokens=True)
                judged=score(row,raw);result[label].append({'id':row['id'],'expected':json.loads(row['messages'][-1]['content']),'output':raw,**judged})
                print(json.dumps({'stage':label,'id':row['id'],'passed':judged['passed']}),flush=True)
                (output/(label+'-partial.json')).write_text(json.dumps(result[label],indent=2,ensure_ascii=False),encoding='utf-8')
    report={'model':MODEL,'revision':REVISION,'contract_version':2,'fixture_sha256':hashlib.sha256(snapshot).hexdigest(),'submitted_count':submitted_count,'capacity_exclusions':oversized,'count':len(rows),'base_passed':sum(r['passed'] for r in result['base']),'adapter_passed':sum(r['passed'] for r in result['adapter']),'cases':result,'gpu':torch.cuda.get_device_name(0),'precision':str(dtype),'max_new_tokens':512,'max_prompt_tokens':max(token_lengths),'runtime_context_budget':4096,'base_outputs_reused_from':args.base_report_from,'developer_authored':True,'blind_human_evaluation':False,'general_factual_accuracy_measured':False,'release_qualified':False}
    report['adapter_profile']=adapter_profile
    report['adapter_unsafe_outputs']=sum(r['unsafe_output'] for r in result['adapter'])
    report['adapterSha256']=hashlib.sha256((Path(args.adapter)/'adapter_model.safetensors').read_bytes()).hexdigest()
    (output/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'}),flush=True)

if __name__=='__main__':main()
