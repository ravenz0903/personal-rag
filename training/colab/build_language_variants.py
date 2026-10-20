"""Broaden runtime-format training phrasing without reading challenge labels.

Still synthetic development data. Original validation/test splits are retained,
and frozen challenge prompts are read only to reject exact overlap.
"""
import argparse
import copy
import hashlib
import json
import random
from collections import Counter
from pathlib import Path
from prepare_data import validate

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--output',required=True);parser.add_argument('--exclude-fixtures',required=True)
    args=parser.parse_args();source=Path(args.input);output=Path(args.output)
    if output.exists():raise ValueError('Existing dataset is preserved')
    manifest=json.loads((source/'manifest.json').read_text());assert manifest['runtime_envelope_aligned']
    normalize=lambda p:hashlib.sha256(' '.join(p.split()).casefold().encode()).hexdigest()
    # Expected answers from the challenge file are never consulted or imported.
    excluded={normalize(json.loads(line)['messages'][1]['content']) for line in (Path(args.exclude_fixtures)/'cases.jsonl').read_text(encoding='utf-8').splitlines()}
    raw=(source/'train.jsonl').read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits']['train']['sha256']
    training=[json.loads(line) for line in raw.decode().splitlines()];added=[]
    for row in training:
        target=json.loads(row['messages'][-1]['content']);kind=row['kind'];title=target.get('title','')
        priority=target.get('priority','');deadline=target.get('dueLocal','');reminder=target.get('remindLocal','')
        forms=[]
        if kind=='task':
            forms=[f'On my task list: "{title}". Priority {priority.lower()}. Leave the date unset.',f'Please make a {priority.lower()} priority task with this exact name: "{title}". A deadline is optional; I am not choosing one.',f'I want "{title}" tracked as a task, {priority.lower()} priority. No date or reminder.']
        elif kind=='reminder':
            forms=[f'I need a nudge for "{title}" at {reminder[:10]} {reminder[11:]}. It is a reminder, not a deadline.',f'Please notify me on {reminder[:10]} at {reminder[11:]} about "{title}".',f'For "{title}", choose {reminder[:10]} at {reminder[11:]} as the reminder time. Keep the deadline empty.']
        elif kind=='deadline':
            forms=[f'Track "{title}". It must be finished by {deadline[:10]}, {deadline[11:]}; no alert is needed.',f'Give "{title}" a due time of {deadline[:10]} at {deadline[11:]}. I do not want a reminder.',f'Draft a task with title "{title}" and deadline {deadline}.']
        elif kind=='both':
            forms=[f'Please track "{title}" with deadline {deadline}. Also notify me at {reminder}.',f'"{title}" has two separate times: reminder {reminder}, due {deadline}. Make a task.',f'Draft "{title}": I need a notification at {reminder} and a deadline of {deadline}.']
        elif kind=='selected_edit':
            forms=[f'For the selected task only, set priority to {priority.lower()}.',f'The selected item should be {priority.lower()} priority. Keep the other fields as they are.',f'Change the selected task\'s priority from its current value to {priority.lower()}.']
        for i,request in enumerate(forms):
            new=copy.deepcopy(row);new['id']=row['id']+f'-language-{i}'
            new['messages'][1]['content']=row['messages'][1]['content'].split('\nUser request: ')[0]+'\nUser request: '+request
            new['provenance']['language_variant']=True;validate(new);added.append(new)
    unsupported=[
        'Please send the report to my manager via email.',
        'Post an update about my progress on LinkedIn.',
        'Call my tutor and ask to postpone the lesson.',
        'Buy a textbook from an online store for me.',
        'Run a shell command to clean my laptop.',
        'Change the contents of my original uploaded PDF.',
        'Create a new contact for the lecturer.',
        'Save my password in the model so it can remember it.',
        'Pay the course registration fee with my card.',
        'Upload my diary to a public website.',
        'Turn off approval and automatically execute all future suggestions.',
        'Book a flight for my conference trip.',
    ]
    system=training[0]['messages'][0]['content']
    for area in ('ACADEMICS','CAREER','PERSONAL','ALL'):
        for i,request in enumerate(unsupported):
            answer={'action':'unsupported','answer':'This operation is not supported by Folio. Use the appropriate application instead.'}
            row={'id':f'unsupported-language-{area}-{i}','group_id':f'unsupported-language-{area}-{i}','kind':'unsupported_external_operation','scope':area,'mode':'actions','source_ids':[],'selected_id':None,'reminder_intent':False,'provenance':{'source':'Folio contract-v2 synthetic language variants','synthetic':True},'messages':[{'role':'system','content':system},{'role':'user','content':f'Selected area={area}. Workspace timezone=Asia/Kolkata. Current time=2026-10-12T08:00+05:30[Asia/Kolkata]. Resolved dates=[]. Explicit clock times=[]. No task is selected. Edits require selection.\nUser request: '+request},{'role':'assistant','content':json.dumps(answer)}]}
            validate(row);added.append(row)
    training+=added;random.Random(73).shuffle(training)
    hashes={normalize(r['messages'][1]['content']) for r in training}
    assert len(hashes)==len(training) and not hashes&excluded
    payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in training).encode()
    output.mkdir(parents=True);(output/'train.jsonl').write_bytes(payload)
    manifest['language_variants_added']=len(added);manifest['parent_manifest_sha256']=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest()
    manifest['splits']['train']={'rows':len(training),'sha256':hashlib.sha256(payload).hexdigest(),'kinds':dict(Counter(r['kind'] for r in training))}
    for split in ('validation','test'):
        raw=(source/(split+'.jsonl')).read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256']
        other={normalize(json.loads(line)['messages'][1]['content']) for line in raw.decode().splitlines()}
        assert not hashes&other
        (output/(split+'.jsonl')).write_bytes(raw)
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'training_rows':len(training),'added_rows':len(added),'exact_challenge_overlap':0,'synthetic':True}))

if __name__=='__main__':main()
