"""Remove spurious fixed-clock cues; preserves requested dates and labels."""
import argparse
import copy
import hashlib
import json
import random
import re
from collections import Counter
from pathlib import Path
from prepare_data import validate

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--output',required=True);parser.add_argument('--exclude-fixtures',action='append',required=True)
    args=parser.parse_args();source=Path(args.input);out=Path(args.output)
    if out.exists():raise ValueError('Existing datasets are preserved')
    manifest=json.loads((source/'manifest.json').read_text());raw=(source/'train.jsonl').read_bytes()
    assert hashlib.sha256(raw).hexdigest()==manifest['splits']['train']['sha256']
    original=[json.loads(line) for line in raw.decode().splitlines()];rows=copy.deepcopy(original);added=[];rng=random.Random(149)
    pattern=r'Current time=(\d{4}-\d{2}-\d{2})T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?\+05:30\[Asia/Kolkata\]'
    for row in original:
        if row['mode']!='actions':continue
        match=re.search(pattern,row['messages'][1]['content']);assert match
        proposal=json.loads(row['messages'][-1]['content'])
        for variant in range(2):
            hour=rng.randrange(24);minute=rng.randrange(60);second=rng.randrange(60)
            clock=f'{hour:02d}:{minute:02d}'
            if proposal.get('remindLocal','').startswith(match.group(1)) and clock>=proposal['remindLocal'][11:]:continue
            stamp=match.group(1)+'T'+clock
            if variant:stamp+=f':{second:02d}.{rng.randrange(10**9):09d}'
            stamp+='+05:30[Asia/Kolkata]'
            new=copy.deepcopy(row);new['id']=row['id']+f'-clock-{variant}'
            new['messages'][1]['content']=re.sub(pattern,'Current time='+stamp,new['messages'][1]['content'])
            new['provenance']['clock_variant']='Only current clock/precision changed; requested dates/times and label preserved'
            validate(new);added.append(new)
    rows+=added;rng.shuffle(rows);norm=lambda text:hashlib.sha256(' '.join(text.split()).casefold().encode()).hexdigest()
    hashes={norm(r['messages'][1]['content']) for r in rows};assert len(hashes)==len(rows)
    for folder in args.exclude_fixtures:
        raw=(Path(folder)/'cases.jsonl').read_bytes();meta=json.loads((Path(folder)/'manifest.json').read_text());assert hashlib.sha256(raw).hexdigest()==meta['sha256']
        assert not hashes&{norm(json.loads(line)['messages'][1]['content']) for line in raw.decode().splitlines()}
    out.mkdir(parents=True);payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();(out/'train.jsonl').write_bytes(payload)
    manifest['clock_variants_added']=len(added);manifest['parent_manifest_sha256']=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest()
    manifest['splits']['train']={'rows':len(rows),'sha256':hashlib.sha256(payload).hexdigest(),'kinds':dict(Counter(r['kind'] for r in rows))}
    for split in ('validation','test'):
        raw=(source/(split+'.jsonl')).read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256']
        assert not hashes&{norm(json.loads(line)['messages'][1]['content']) for line in raw.decode().splitlines()}
        (out/(split+'.jsonl')).write_bytes(raw)
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'rows':len(rows),'clock_variants':len(added),'labels_changed':False,'frozen_prompt_overlap':0}))

if __name__=='__main__':main()
