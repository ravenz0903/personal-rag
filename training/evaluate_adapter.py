"""Compare original untouched synthetic benchmark on the same quantized base/adapter."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adapter', default='target/folio-adapter-pilot')
    parser.add_argument('--suite', choices=['original','fresh','qualification'], default='original')
    parser.add_argument('--adapter-only', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    adapter = Path(args.adapter).resolve()
    if not adapter.is_relative_to(root / 'target'):
        raise ValueError('Use an ignored development adapter under target/')
    report = json.loads((adapter / 'pilot-report.json').read_text(encoding='utf-8'))
    os.environ['HF_HOME'] = str(root / 'target/ai-training-cache')
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM, BitsAndBytesConfig
    from peft import PeftModel, prepare_model_for_kbit_training
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA evaluation unavailable')
    suite_module = {'fresh':'./desktop/ai/holdout.cjs','original':'./desktop/ai/evaluation.cjs','qualification':'./desktop/ai/qualification.cjs'}[args.suite]
    benchmark = json.loads(subprocess.check_output(
        ['node', '-e', "const e=require(process.argv[1]);process.stdout.write(JSON.stringify({cases:e.cases,system:e.system}))",suite_module],
        cwd=root, text=True, encoding='utf-8'))
    tokenizer = AutoTokenizer.from_pretrained(adapter, local_files_only=True)
    quant = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_quant_type='nf4',
                              bnb_4bit_use_double_quant=True, bnb_4bit_compute_dtype=torch.bfloat16)
    base = AutoModelForCausalLM.from_pretrained(report['model'], revision=report['revision'],
        local_files_only=True, trust_remote_code=False, quantization_config=quant,
        device_map={'': 0}, dtype=torch.bfloat16, attn_implementation='eager')
    base = prepare_model_for_kbit_training(base, use_gradient_checkpointing=False)
    model = PeftModel.from_pretrained(base, adapter, is_trainable=False)
    model.eval()
    results = {}
    def run():
        rows = []
        with torch.no_grad():
            for case in benchmark['cases']:
                prompt = tokenizer.apply_chat_template([
                    {'role': 'system', 'content': benchmark['system']},
                    {'role': 'user', 'content': case['prompt']}],
                    tokenize=False, add_generation_prompt=True, enable_thinking=False)
                inputs = tokenizer(prompt, return_tensors='pt', add_special_tokens=False).to('cuda')
                generated = model.generate(**inputs, max_new_tokens=384, do_sample=False,
                    use_cache=True, pad_token_id=tokenizer.eos_token_id)
                raw = tokenizer.decode(generated[0, inputs.input_ids.shape[1]:], skip_special_tokens=True)
                rows.append({'id': case['id'], 'output': raw})
                print(case['id'], flush=True)
        return rows
    if not args.adapter_only:
        with model.disable_adapter():
            results['base'] = run()
    results['adapter'] = run()
    # Reuse the actual comparison scorer rather than copying its semantics.
    scored = subprocess.check_output(['node', '-e',
        "const e=require(process.argv[1]);let s='';process.stdin.on('data',x=>s+=x);process.stdin.on('end',()=>{const r=JSON.parse(s);for(const rows of Object.values(r)){for(const row of rows){let p;try{p=JSON.parse(row.output)}catch{}const c=e.cases.find(c=>c.id===row.id);row.failures=e.score(c,p);row.passed=!row.failures.length;row.unsafe=e.unsafe?e.unsafe(c,p):null}}process.stdout.write(JSON.stringify(r))})",suite_module],
        input=json.dumps(results), cwd=root, text=True, encoding='utf-8')
    results = json.loads(scored)
    summary = {name: sum(row['passed'] for row in rows) for name, rows in results.items()}
    filename={'fresh':'fresh-report.json','original':'benchmark-report.json','qualification':'qualification-report.json'}[args.suite]
    (adapter / filename).write_text(json.dumps({
        'runtime': 'Transformers NF4 (not Ollama GGUF)', 'model': report['model'],
        'revision': report['revision'], 'passed': summary, 'cases': results,
        'suite':args.suite,'synthetic': True, 'actions_executed': False,
        'unsafe_cases':{name:[row['id'] for row in rows if row.get('unsafe')] for name,rows in results.items()}}, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary), flush=True)


if __name__ == '__main__':
    main()
