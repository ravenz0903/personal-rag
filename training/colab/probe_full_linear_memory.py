"""Measure widest-input CUDA training memory without changing adapter weights."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from adapter_profiles import PROFILES,identify_profile,inherit_attention_weights
from prepare_data import MODEL,REVISION,validate


def main():
    p=argparse.ArgumentParser();p.add_argument('--parent',required=True);p.add_argument('--data',required=True);p.add_argument('--base-path',required=True);p.add_argument('--output',required=True);p.add_argument('--max-length',type=int,default=1536);p.add_argument('--optimizer-steps',type=int,default=2);p.add_argument('--accumulation',type=int,default=4);args=p.parse_args()
    assert 2<=args.optimizer_steps<=4 and 1<=args.accumulation<=4
    root=Path(__file__).resolve().parents[2];output=Path(args.output).resolve();parent=Path(args.parent).resolve();data=Path(args.data).resolve()
    if not output.is_relative_to(root/'target') or output.exists():raise ValueError('Use a fresh ignored probe report')
    assert identify_profile(json.loads((parent/'adapter_config.json').read_text()))=='attention'
    manifest=json.loads((data/'manifest.json').read_text());blob=(data/'train.jsonl').read_bytes()
    assert hashlib.sha256(blob).hexdigest()==manifest['splits']['train']['sha256']
    os.environ['HF_HUB_OFFLINE']='1';os.environ['HF_HUB_DISABLE_TELEMETRY']='1'
    import torch
    from transformers import AutoTokenizer,AutoModelForCausalLM,BitsAndBytesConfig
    from peft import LoraConfig,get_peft_model,get_peft_model_state_dict,prepare_model_for_kbit_training
    from safetensors.torch import load_file
    assert torch.cuda.is_available() and torch.cuda.is_bf16_supported()
    torch.manual_seed(713);torch.cuda.manual_seed_all(713)
    tokenizer=AutoTokenizer.from_pretrained(args.base_path,local_files_only=True,trust_remote_code=False)
    longest=None
    for line in blob.decode().splitlines():
        row=json.loads(line);validate(row)
        prompt=tokenizer.apply_chat_template(row['messages'][:-1],tokenize=False,add_generation_prompt=True,enable_thinking=False)
        prefix=tokenizer(prompt,add_special_tokens=False)['input_ids']
        answer=tokenizer(row['messages'][-1]['content']+tokenizer.eos_token,add_special_tokens=False)['input_ids']
        ids=prefix+answer
        if len(ids)>args.max_length:raise ValueError('Oversized row; never truncate probe input')
        if longest is None or len(ids)>len(longest[1]):longest=(row['id'],ids,[-100]*len(prefix)+answer)
    print(json.dumps({'stage':'probe-input','row':longest[0],'tokens':len(longest[1]),'truncated':False}),flush=True)
    model=AutoModelForCausalLM.from_pretrained(args.base_path,local_files_only=True,trust_remote_code=False,device_map={'':0},dtype=torch.bfloat16,attn_implementation='sdpa',quantization_config=BitsAndBytesConfig(load_in_4bit=True,bnb_4bit_quant_type='nf4',bnb_4bit_use_double_quant=True,bnb_4bit_compute_dtype=torch.bfloat16))
    model=prepare_model_for_kbit_training(model,use_gradient_checkpointing=True)
    model=get_peft_model(model,LoraConfig(r=8,lora_alpha=16,lora_dropout=0.05,target_modules=list(PROFILES['full-linear']),bias='none',task_type='CAUSAL_LM'))
    inheritance=inherit_attention_weights(model,load_file(str(parent/'adapter_model.safetensors'),device='cpu'))
    def state_hash():
        digest=hashlib.sha256()
        for key,value in sorted(get_peft_model_state_dict(model).items()):
            digest.update(key.encode());digest.update(value.detach().cpu().contiguous().numpy().tobytes())
        return digest.hexdigest()
    initial_hash=state_hash()
    inputs=torch.tensor([longest[1]],device='cuda');mask=torch.ones_like(inputs)
    # Disabled added modules exactly represent the inherited q/v forward path.
    extra=[module for name,module in model.named_modules() if any(name.endswith('.'+target) for target in set(PROFILES['full-linear'])-set(PROFILES['attention'])) and hasattr(module,'enable_adapters')]
    model.eval()
    with torch.inference_mode(),torch.autocast('cuda',dtype=torch.bfloat16):
        for module in extra:module.enable_adapters(False)
        reference=model(input_ids=inputs,attention_mask=mask,use_cache=False).logits[:,-1].cpu()
        for module in extra:module.enable_adapters(True)
        expanded=model(input_ids=inputs,attention_mask=mask,use_cache=False).logits[:,-1].cpu()
    difference=float((reference-expanded).abs().max())
    if difference!=0:raise ValueError('Initial CUDA forward parity failed: '+str(difference))
    model.config.use_cache=False;model.train()
    parameters=[parameter for parameter in model.parameters() if parameter.requires_grad]
    # Zero LR still materializes real Adam states. A before/after hash verifies
    # the probe did not optimize or alter the inherited model's weights.
    optimizer=torch.optim.AdamW(parameters,lr=0.0)
    labels=torch.tensor([longest[2]],device='cuda');torch.cuda.reset_peak_memory_stats()
    for step in range(args.optimizer_steps):
        optimizer.zero_grad(set_to_none=True)
        for micro in range(args.accumulation):
            with torch.autocast('cuda',dtype=torch.bfloat16):loss=model(input_ids=inputs,attention_mask=mask,labels=labels).loss/args.accumulation
            if not torch.isfinite(loss):raise ValueError('Non-finite probe loss')
            loss.backward()
        torch.nn.utils.clip_grad_norm_(parameters,1.0,error_if_nonfinite=True);optimizer.step();torch.cuda.synchronize()
        print(json.dumps({'stage':'zero-update-memory-probe','optimizer_step':step+1,'microbatches':args.accumulation,'peak_allocated_bytes':torch.cuda.max_memory_allocated(),'peak_reserved_bytes':torch.cuda.max_memory_reserved()}),flush=True)
    peak=torch.cuda.max_memory_allocated();reserved=torch.cuda.max_memory_reserved()
    final_hash=state_hash()
    if initial_hash!=final_hash:raise ValueError('Zero-rate probe altered adapter weights')
    report={'model':MODEL,'revision':REVISION,'profile':'full-linear','parent_adapter_sha256':hashlib.sha256((parent/'adapter_model.safetensors').read_bytes()).hexdigest(),'data_sha256':hashlib.sha256(blob).hexdigest(),'row_id':longest[0],'tokens':len(longest[1]),'truncated':False,'gpu':torch.cuda.get_device_name(0),'total_vram_bytes':torch.cuda.get_device_properties(0).total_memory,'trainable_parameters':sum(parameter.numel() for parameter in parameters),'added_modules':len(extra),'initial_cuda_max_logit_difference':difference,'peak_allocated_bytes':peak,'peak_reserved_bytes':reserved,'optimizer_parameter_states':len(optimizer.state),'learning_rate':0.0,'weights_unchanged':True,'initial_state_sha256':initial_hash,'final_state_sha256':final_hash,'inheritance':inheritance,'quality_improvement_verified':False,'release_qualified':False,'deployed':False}
    report.update({'optimizer_steps':args.optimizer_steps,'accumulation':args.accumulation,'total_max_length_microbatches':args.optimizer_steps*args.accumulation,'persistent_optimizer_states_exercised':True})
    output.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
