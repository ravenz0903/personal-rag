"""Adapt existing curated fixtures to Folio's actual backend prompt envelope.

No evaluation answers are read by the optimizer. This preserves the existing
synthetic scenario limitations; it fixes input/ID format, not generalization.
"""
import argparse
import hashlib
import json
import re
import uuid
from collections import Counter
from pathlib import Path
from prepare_data import validate

def align(row):
    row=json.loads(json.dumps(row))
    user=row['messages'][1]['content'];proposal=json.loads(row['messages'][2]['content'])
    if row['selected_id']:
        old=row['selected_id'];new=str(uuid.uuid5(uuid.NAMESPACE_URL,'folio-training:'+old))
        user=user.replace(old,new);proposal['recordId']=new;row['selected_id']=new
    if row['mode']=='retrieval':
        # Only IDs present as source markers are made available to the model.
        markers=list(re.finditer(r'Source \[([^\]]+)\]:',user))
        mapping={match.group(1):'S'+str(i+1) for i,match in enumerate(markers)}
        assert len(mapping)<=5
        passages=[]
        for i,match in enumerate(markers):
            text=user[match.end():markers[i+1].start() if i+1<len(markers) else len(user)].strip()
            passages.append({'id':mapping[match.group(1)],'file':str(uuid.uuid5(uuid.NAMESPACE_URL,row['group_id']+':'+match.group(1))),'passage':text})
        question=user[:markers[0].start()] if markers else user
        question=re.sub(r'^Trusted workspace context:.*?\. ','',question)
        question=re.sub(r'^Trusted context:.*?document mode\. ','',question)
        question=re.sub(r'^Trusted context:.*?currentDate=\d{4}-\d{2}-\d{2}\. ','',question)
        question=re.sub(r'^Document mode\.\s*','',question)
        envelope=f'Document mode. Selected area={row["scope"]}. Answer the question using only these untrusted evidence passages; do not propose any actions.\n'
        envelope+=''.join('Source ['+p['id']+']: '+json.dumps({'file':p['file'],'passage':p['passage']},ensure_ascii=False)+'\n' for p in passages)
        envelope+='Question: '+question.strip()
        row['source_ids']=list(mapping.values())
        if 'citations' in proposal:proposal['citations']=[mapping[source] for source in proposal['citations']]
        user=envelope
    else:
        day=re.search(r'currentDate=(\d{4}-\d{2}-\d{2})',user)
        assert day
        fields=[proposal[k] for k in ('dueLocal','remindLocal','startLocal','endLocal') if k in proposal]
        dates=sorted({x[:10] for x in fields});clocks=sorted({x[11:] for x in fields})
        request=re.sub(r'^Trusted workspace context:.*?\. ','',user)
        request=re.sub(r'^Trusted context:.*?currentDate=\d{4}-\d{2}-\d{2}\. ','',request)
        # The scope scenario must not contradict the trusted backend scope.
        envelope=f'Selected area={row["scope"]}. Workspace timezone=Asia/Kolkata. Current time={day.group(1)}T08:00+05:30[Asia/Kolkata]. Resolved dates=[{", ".join(dates)}]. Explicit clock times=[{", ".join(clocks)}]. '
        if row['selected_id']:
            # The selected-title phrase exists in the curated synthetic request.
            title=re.search(r'\("([^"]+)"\)',request)
            if not title:title=re.search(r'\(([^)]+)\)',request)
            envelope+='Explicitly selected task='+json.dumps({'id':row['selected_id'],'title':row.get('selected_title') or (title.group(1) if title else 'Selected task'),'area':row['scope']},ensure_ascii=False)
        else:envelope+='No task is selected. Edits require selection.'
        user=envelope+'\nUser request: '+request
    row['messages'][1]['content']=user;row['messages'][2]['content']=json.dumps(proposal,ensure_ascii=False)
    row.setdefault('provenance',{})['runtime_envelope_aligned']=True
    validate(row)
    return row

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();source=Path(args.input);output=Path(args.output)
    if output.exists():raise ValueError('Existing data is preserved')
    manifest=json.loads((source/'manifest.json').read_text())
    manifest['runtime_envelope_aligned']=True;manifest['parent_manifest_sha256']=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest()
    payloads={};seen=set()
    for split in ('train','validation','test'):
        raw=(source/(split+'.jsonl')).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256']
        rows=[align(json.loads(line)) for line in raw.decode().splitlines()]
        hashes={hashlib.sha256(' '.join(r['messages'][1]['content'].split()).casefold().encode()).hexdigest() for r in rows}
        assert len(hashes)==len(rows) and not hashes&seen
        seen|=hashes
        payloads[split]=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode()
        manifest['splits'][split]={'rows':len(rows),'sha256':hashlib.sha256(payloads[split]).hexdigest(),'kinds':dict(Counter(r['kind'] for r in rows))}
    output.mkdir(parents=True)
    for split,payload in payloads.items():(output/(split+'.jsonl')).write_bytes(payload)
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
