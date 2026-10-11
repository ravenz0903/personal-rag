"""Focused calendar/clarification development examples, plus broad successful replay."""
import calendar
import hashlib
import json
import subprocess
from datetime import date, timedelta
from pathlib import Path
from build_hard_dataset import ROOT, build, contract, prompt_hash
from build_robust_dataset import row, build as replay
from build_runtime_dataset import build as runtime_replay, runtime_system

def focused(split, groups):
    legacy = contract('./desktop/ai/holdout.cjs'); app = contract('./desktop/ai/contract.cjs')
    rows = []
    for i in range(groups):
        year = (2024 if split == 'train' else 2072) + 4*(i%15)
        title = ('Examine minerals' if split == 'train' else 'Sort worksheets') + f' {i+2}'
        area = ['ACADEMICS','CAREER','PERSONAL'][i%3]
        label = {'ACADEMICS':'Academic','CAREER':'Career','PERSONAL':'Personal'}[area]
        hour = f'{8+i%12:02d}:{[10,20,30,40,50][i%5]:02d}'
        leap_day = date(year,2,28); ordinary_day = date(year+1,2,28)
        next_leap = leap_day + timedelta(days=1); next_ordinary = ordinary_day + timedelta(days=1)
        phrase = ['morning','afternoon','evening','after lunch','sometime tomorrow'][i%5]
        for kind, (day, next_day) in enumerate([(leap_day,next_leap),(ordinary_day,next_ordinary)]):
            forms = [f'Today is {day}. In {label} remind me tomorrow at {hour} to {title}.',
                     f'The date today is {day}. For {label}, set a reminder tomorrow {hour}: {title}.',
                     f'{label} reminder for {title}, tomorrow at {hour}; current date {day}.']
            prompt = forms[i%3]
            rows.append(row(f'boundary-{split}-{i}-{kind}',kind,legacy,prompt,
                {'action':'create_task','area':area,'title':title,'dueLocal':str(next_day)+'T'+hour}))
            rows.append(row(f'boundary-app-{split}-{i}-{kind}',kind,app,
                prompt+f' Folio resolved tomorrow={next_day}.',
                {'action':'create_task','area':area,'title':title,'remindLocal':str(next_day)+'T'+hour}))
        ambiguous = [f'Today is {leap_day}. Put a {label} event "{title}" in my calendar tomorrow {phrase}.',
                     f'Arrange a {label} meeting called {title} for {next_leap} {phrase}.',
                     f'Add {title} to my {label} calendar {phrase}. Today is {leap_day}.'][i%3]
        for k, system in enumerate([legacy,app]):
            rows.append(row(f'boundary-unclear-{split}-{i}-{k}',2,system,ambiguous + (f' Selected workspace area={area}.' if k else ''),
                {'action':'clarify','answer':'What exact date, start time, and end time should I use?'}))
        exact = f'{label} event "{title}": {next_leap} from 09:20 until 10:40.'
        for k, system in enumerate([legacy,app]):
            rows.append(row(f'boundary-exact-{split}-{i}-{k}',3,system,exact + (f' Selected workspace area={area}.' if k else ''),
                {'action':'create_event','area':area,'title':title,'startLocal':str(next_leap)+'T09:20','endLocal':str(next_leap)+'T10:40'}))
    return rows

def main():
    frozen = json.loads((ROOT/'target/ai-qualification-v1/manifest.json').read_text())
    seen = set(frozen['promptHashes'])
    old = json.loads(subprocess.check_output(['node','-e',"process.stdout.write(JSON.stringify([...require('./desktop/ai/evaluation.cjs').cases,...require('./desktop/ai/holdout.cjs').cases].map(c=>c.prompt)))"],cwd=ROOT,text=True,encoding='utf-8'))
    seen.update(prompt_hash(prompt) for prompt in old)
    # System text is part of the example identity: the same instruction can use
    # different reminder fields in immutable legacy tests and application v2.
    splits = {'train': focused('train',40)+build('train',16)+runtime_replay('train',12,runtime_system())+replay('train',8),
              'validation': focused('validation',2)}
    identities = set(); folder = Path(__file__).parent/'data'
    manifest = {'synthetic':True,'version':6,'benchmark_aware':True,'benchmark_prompts_optimized':False,
                'qualification_sha256':frozen['sha256'],'counts':{}}
    for split, rows in splits.items():
        for item in rows:
            prompt = item['messages'][1]['content']; key = prompt_hash(prompt)
            if prompt_hash(prompt) in seen or key in identities: raise ValueError('Repeated or qualification prompt')
            identities.add(key)
        data = ''.join(json.dumps(item,ensure_ascii=False)+'\n' for item in rows).encode()
        (folder/(split+'.jsonl')).write_bytes(data)
        manifest['counts'][split] = {'rows':len(rows),'sha256':hashlib.sha256(data).hexdigest()}
    (folder/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest))

if __name__ == '__main__': main()
