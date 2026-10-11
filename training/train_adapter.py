"""Bounded QLoRA pilot. Outputs adapter weights only to ignored target/, never modifies Folio."""
import argparse, json, random, time, os
from pathlib import Path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--model',default='Qwen/Qwen3-4B');parser.add_argument('--revision',required=True);parser.add_argument('--steps',type=int,default=20);parser.add_argument('--output',default='target/folio-adapter-pilot');parser.add_argument('--validation-limit',type=int,default=12);parser.add_argument('--resume-adapter');parser.add_argument('--learning-rate',type=float,default=1e-4);parser.add_argument('--skip-before-generation',action='store_true');args=parser.parse_args()
    if len(args.revision)!=40 or any(c not in '0123456789abcdef' for c in args.revision):raise ValueError('Pin a model commit SHA')
    if not 1<=args.steps<=2000:raise ValueError('Experiment steps must be between 1 and 2000')
    if not 0<args.learning_rate<=1e-3:raise ValueError('Invalid learning rate')
    os.environ['HF_HOME']=str(Path(__file__).resolve().parent.parent/'target/ai-training-cache')
    os.environ['HF_HUB_DISABLE_TELEMETRY']='1'
    os.environ['HF_HUB_DISABLE_XET']='1'
    import torch
    from transformers import AutoTokenizer,AutoModelForCausalLM,BitsAndBytesConfig
    from peft import LoraConfig,get_peft_model,prepare_model_for_kbit_training,PeftModel
    if not torch.cuda.is_available():raise RuntimeError('CUDA training unavailable; no silent CPU fallback')
    torch.manual_seed(42);random.seed(42)
    output=Path(args.output).resolve();target=(Path(__file__).resolve().parent.parent/'target').resolve()
    if not output.is_relative_to(target):raise ValueError('Training output must remain under target/')
    if output.exists() and any(output.iterdir()):raise ValueError('Use a new output directory; existing adapters are preserved')
    output.mkdir(parents=True,exist_ok=True)
    tokenizer=AutoTokenizer.from_pretrained(args.model,revision=args.revision,trust_remote_code=False)
    tokenizer.pad_token=tokenizer.eos_token
    quant=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=torch.bfloat16)
    model=AutoModelForCausalLM.from_pretrained(args.model,revision=args.revision,trust_remote_code=False,quantization_config=quant,device_map={'':0},dtype=torch.bfloat16,attn_implementation='eager')
    model=prepare_model_for_kbit_training(model,use_gradient_checkpointing=True)
    if args.resume_adapter:
        parent=Path(args.resume_adapter).resolve()
        if not parent.is_relative_to(target):raise ValueError('Parent adapter must be under target/')
        previous=json.loads((parent/'pilot-report.json').read_text(encoding='utf-8'))
        if previous['model']!=args.model or previous['revision']!=args.revision:raise ValueError('Parent base model mismatch')
        model=PeftModel.from_pretrained(model,parent,is_trainable=True)
    else:
        model=get_peft_model(model,LoraConfig(r=8,lora_alpha=16,lora_dropout=0.05,target_modules=['q_proj','v_proj'],bias='none',task_type='CAUSAL_LM'))
    model.config.use_cache=False;model.print_trainable_parameters()
    train=[json.loads(line) for line in (Path(__file__).parent/'data/train.jsonl').read_text(encoding='utf-8').splitlines()]
    validation=[json.loads(line) for line in (Path(__file__).parent/'data/validation.jsonl').read_text(encoding='utf-8').splitlines()]
    if not 1<=args.validation_limit<=len(validation):raise ValueError('Invalid validation limit')
    validation_used=validation[:args.validation_limit]
    def encode(row):
        prompt=tokenizer.apply_chat_template(row['messages'][:-1],tokenize=False,add_generation_prompt=True,enable_thinking=False)
        prompt_ids=tokenizer(prompt,add_special_tokens=False)['input_ids'];answer_ids=tokenizer(row['messages'][-1]['content']+tokenizer.eos_token,add_special_tokens=False)['input_ids']
        if len(prompt_ids)+len(answer_ids)>512:raise ValueError('Example exceeds pilot sequence budget: '+row['id'])
        ids=torch.tensor([prompt_ids+answer_ids],device='cuda');labels=ids.clone();labels[:,:len(prompt_ids)]=-100
        return {'input_ids':ids,'attention_mask':torch.ones_like(ids),'labels':labels}
    # This validation set is never passed to the optimizer.
    def loss():
        model.eval();values=[]
        with torch.no_grad():
            for row in validation_used:values.append(float(model(**encode(row)).loss))
        return sum(values)/len(values)
    def answers():
        model.eval();results=[]
        with torch.no_grad():
            for row in validation_used:
                prompt=tokenizer.apply_chat_template(row['messages'][:-1],tokenize=False,add_generation_prompt=True,enable_thinking=False)
                inputs=tokenizer(prompt,return_tensors='pt',add_special_tokens=False).to('cuda')
                generated=model.generate(**inputs,max_new_tokens=192,do_sample=False,pad_token_id=tokenizer.eos_token_id,use_cache=True)
                raw=tokenizer.decode(generated[0,inputs.input_ids.shape[1]:],skip_special_tokens=True)
                expected=json.loads(row['messages'][-1]['content'])
                try: actual=json.loads(raw)
                except (ValueError,TypeError): actual={}
                fields={k:v for k,v in expected.items() if k!='answer'}
                matched=isinstance(actual,dict) and all(actual.get(k)==v for k,v in fields.items())
                results.append({'id':row['id'],'expected':expected,'output':raw,'structured_fields_match':matched})
                print(json.dumps({'evaluation':row['id'],'structured_fields_match':matched}),flush=True)
        return results
    # Check every example before performing any optimization.
    for row in train+validation: encode(row)
    base_answers=None if args.skip_before_generation else answers();before=loss();model.train();optimizer=torch.optim.AdamW((p for p in model.parameters() if p.requires_grad),lr=args.learning_rate)
    random.shuffle(train);history=[];start=time.time();torch.cuda.reset_peak_memory_stats()
    for step in range(args.steps):
        batch=encode(train[step%len(train)]);result=model(**batch);result.loss.backward();torch.nn.utils.clip_grad_norm_(model.parameters(),1.0);optimizer.step();optimizer.zero_grad(set_to_none=True)
        history.append(float(result.loss.detach()));print(json.dumps({'step':step+1,'loss':history[-1]}),flush=True)
    model.save_pretrained(output,safe_serialization=True);tokenizer.save_pretrained(output)
    (output/'training-state.json').write_text(json.dumps({'steps_completed':args.steps,'evaluation_complete':False,'deployed':False}),encoding='utf-8')
    after=loss();adapter_answers=answers()
    report={'model':args.model,'revision':args.revision,'steps':args.steps,'optimized_row_ids':[train[i%len(train)]['id'] for i in range(args.steps)],'training_rows':len(train),'validation_rows':len(validation),'loss_validation_rows':len(validation_used),'validation_loss_before':before,'validation_loss_after':after,'base_answers':base_answers,'adapter_answers':adapter_answers,'train_losses':history,'seconds':time.time()-start,'peak_allocated_vram_bytes':torch.cuda.max_memory_allocated(),'torch':torch.__version__,'pilot_only':True,'deployed':False}
    report.update({'resume_adapter':args.resume_adapter,'learning_rate':args.learning_rate,'optimizer_restarted':bool(args.resume_adapter),'before_generation_measured':not args.skip_before_generation})
    import shutil
    for filename in ('train.jsonl','validation.jsonl','manifest.json'):
        shutil.copyfile(Path(__file__).parent/'data'/filename,output/filename)
    (output/'pilot-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report),flush=True)
    (output/'training-state.json').write_text(json.dumps({'steps_completed':args.steps,'evaluation_complete':True,'deployed':False}),encoding='utf-8')

if __name__=='__main__':main()
