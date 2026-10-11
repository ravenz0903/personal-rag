"""Cross area/year examples to remove correlations in earlier date training."""
import hashlib,json,subprocess
from datetime import date,timedelta
from pathlib import Path
from build_hard_dataset import ROOT,build,contract,prompt_hash
from build_boundary_dataset import focused
from build_robust_dataset import row,build as replay
from build_runtime_dataset import build as runtime_replay,runtime_system

def leap_examples(split,groups):
    system=contract('./desktop/ai/holdout.cjs');app=contract('./desktop/ai/contract.cjs');result=[]
    for i in range(groups):
        year=(2028 if split=='train' else 2104)+4*((i//3)%16)
        area=['ACADEMICS','CAREER','PERSONAL'][i%3];label={'ACADEMICS':'Academic','CAREER':'Career','PERSONAL':'Personal'}[area]
        title=(['Study geology','Review diagrams','Arrange materials','Practice dialogue','Plan exercise','Check instruments'] if split=='train' else ['Measure specimens','Check annotations'])[i%(6 if split=='train' else 2)]+f' {i+1}'
        hour=f'{[7,9,13,16,20,21][(i//16)%6]:02d}:{[5,15,25,35,45,55][i%6]:02d}'
        day=date(year,2,28);tomorrow=day+timedelta(days=1);ordinary=date(year+1,2,28);after=ordinary+timedelta(days=1)
        prompt=f'Today is {day}. In {label} remind me tomorrow at {hour} to {title}.'
        result.append(row(f'leap-{split}-{i}-legacy',0,system,prompt,{'action':'create_task','area':area,'title':title,'dueLocal':str(tomorrow)+'T'+hour}))
        result.append(row(f'leap-{split}-{i}-app',1,app,prompt+f' Folio resolved tomorrow={tomorrow}.',{'action':'create_task','area':area,'title':title,'remindLocal':str(tomorrow)+'T'+hour}))
        result.append(row(f'leap-{split}-{i}-short',2,system,f'{label} reminder tomorrow {hour}: {title}. Current date={day}.',{'action':'create_task','area':area,'title':title,'dueLocal':str(tomorrow)+'T'+hour}))
        result.append(row(f'leap-{split}-{i}-ordinary',3,system,f'Today is {ordinary}. In {label} remind me tomorrow at {hour} to {title}.',{'action':'create_task','area':area,'title':title,'dueLocal':str(after)+'T'+hour}))
    return result

def main():
    frozen=json.loads((ROOT/'target/ai-qualification-v1/manifest.json').read_text());reserved=set(frozen['promptHashes'])
    old=json.loads(subprocess.check_output(['node','-e',"process.stdout.write(JSON.stringify([...require('./desktop/ai/evaluation.cjs').cases,...require('./desktop/ai/holdout.cjs').cases].map(c=>c.prompt)))"],cwd=ROOT,text=True,encoding='utf-8'));reserved.update(prompt_hash(p) for p in old)
    splits={'train':leap_examples('train',96)+focused('train',20)+build('train',12)+runtime_replay('train',12,runtime_system())+replay('train',8),'validation':leap_examples('validation',3)}
    folder=Path(__file__).parent/'data';seen=set();manifest={'synthetic':True,'version':7,'benchmark_aware':True,'benchmark_prompts_optimized':False,'qualification_sha256':frozen['sha256'],'counts':{}}
    for split,examples in splits.items():
        for item in examples:
            key=prompt_hash(item['messages'][1]['content'])
            if key in seen or key in reserved:raise ValueError('Prompt overlap detected')
            seen.add(key)
        content=''.join(json.dumps(x,ensure_ascii=False)+'\n' for x in examples).encode();(folder/(split+'.jsonl')).write_bytes(content)
        manifest['counts'][split]={'rows':len(examples),'sha256':hashlib.sha256(content).hexdigest()}
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8');print(json.dumps(manifest))

if __name__=='__main__':main()
