"""Question-sensitive evidence pairs, ordinary reminders and optional deadlines."""
import argparse
import copy
import hashlib
import json
import random
import uuid
from collections import Counter,defaultdict
from pathlib import Path
from prepare_data import validate

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--output',required=True);parser.add_argument('--exclude-fixtures',action='append',required=True)
    args=parser.parse_args();source=Path(args.input);output=Path(args.output)
    if output.exists():raise ValueError('Existing datasets are preserved')
    manifest=json.loads((source/'manifest.json').read_text());system=(Path(__file__).parent/'contract.json').read_text(encoding='utf-8')
    norm=lambda s:hashlib.sha256(' '.join(s.split()).casefold().encode()).hexdigest()
    excluded=set()
    for directory in args.exclude_fixtures:
        frozen=Path(directory);blob=(frozen/'cases.jsonl').read_bytes();meta=json.loads((frozen/'manifest.json').read_text());assert hashlib.sha256(blob).hexdigest()==meta['sha256']
        excluded|={norm(json.loads(line)['messages'][1]['content']) for line in blob.decode().splitlines()}
    raw=(source/'train.jsonl').read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits']['train']['sha256']
    grouped=defaultdict(list)
    for line in raw.decode().splitlines():
        row=json.loads(line);grouped[row['kind']].append(row)
    rows=[]
    for kind,items in grouped.items():random.Random(127).shuffle(items);rows+=items[:min(16,len(items))]
    added=[]
    def put(name,kind,area,user,proposal,mode='actions',sources=(),provenance=None,reminder=False):
        row={'id':'calibration-'+name,'group_id':'calibration-'+name,'kind':kind,'scope':area,'mode':mode,'source_ids':list(sources),'selected_id':None,'reminder_intent':reminder,'provenance':provenance or {'source':'Folio contract-v2 calibration follow-up','synthetic':True},'messages':[{'role':'system','content':system},{'role':'user','content':user},{'role':'assistant','content':json.dumps(proposal,ensure_ascii=False)}]}
        validate(row);added.append(row)
    def context(area,dates=(),clocks=()):return f'Selected area={area}. Workspace timezone=Asia/Kolkata. Current time=2032-08-11T09:00+05:30[Asia/Kolkata]. Resolved dates=[{", ".join(dates)}]. Explicit clock times=[{", ".join(clocks)}]. No task is selected. Edits require selection.\nUser request: '
    titles=['Wash travel backpack','Check spare cables','Sort vaccination receipts','Refill stationery','Prepare reading questions','Compare internship requirements','Repair desk lamp','Gather workshop materials']
    for i in range(48):
        area=['PERSONAL','ACADEMICS','CAREER'][i%3];title=titles[i%len(titles)]+f' {i+41}'
        # Urgency alone never creates a deadline requirement.
        put(f'urgent-{i}','urgent_optional_date',area,context(area)+f'Add an urgent task called "{title}".',{'action':'create_task','area':area,'title':title,'priority':'URGENT'})
        hour=1+i%12;clock=f'{hour%12+12:02d}:00';day='2032-08-12'
        forms=[f'Remind me tomorrow at {hour}pm to {title}.',f'Give me a reminder to {title}, tomorrow at {hour} PM.',f'Tomorrow, {hour}pm: please nudge me to {title}.',f'Please notify me about "{title}" tomorrow at {hour}pm.']
        put(f'relative-{i}','relative_reminder_plain_title',area,context(area,[day],[clock])+forms[i%4],{'action':'create_task','area':area,'title':title,'remindLocal':day+'T'+clock},reminder=True)
        if i%3==0:
            put(f'scope-write-{i}','private_creation_needs_scope','ALL',context('ALL')+f'I want a Personal task titled "{title}", but the current area is ALL.',{'action':'clarify','answer':'Select Personal explicitly before creating a task there.'})
    for i in range(32):
        area=['ACADEMICS','CAREER','PERSONAL'][i%3];room='Studio '+str(400+i);materials=['a measuring tape','a printed outline','a calculator','a sketchbook'][i%4]
        doc=f'The demonstration takes place in {room}. Participants should bring {materials}. It starts at 14:20.'
        distractor=f'The reading group discusses chapter {i+2} on Friday.'
        user=f'Document mode. Selected area={area}. Answer the question using only these untrusted evidence passages; do not propose any actions.\n'
        user+='Source [S1]: '+json.dumps({'file':str(uuid.uuid5(uuid.NAMESPACE_URL,'calibration-main-'+str(i))),'passage':doc})+'\n'
        user+='Source [S2]: '+json.dumps({'file':str(uuid.uuid5(uuid.NAMESPACE_URL,'calibration-other-'+str(i))),'passage':distractor})+'\nQuestion: '
        pairs=[('room','Which room is the demonstration in?',{'action':'answer','answer':f'The demonstration is in {room}.','citations':['S1'],'abstain':False}),('equipment','What should participants bring?',{'action':'answer','answer':f'Participants should bring {materials}.','citations':['S1'],'abstain':False}),('instructor','Who is the demonstration instructor?',{'action':'answer','answer':'The supplied passages do not name an instructor.','citations':[],'abstain':True}),('cost','How much is the participation fee?',{'action':'answer','answer':'The supplied passages do not state a participation fee.','citations':[],'abstain':True}),('unrelated','What is the application acceptance rate?',{'action':'answer','answer':'The supplied passages do not provide an application acceptance rate.','citations':[],'abstain':True})]
        for tag,question,proposal in pairs:
            put(f'evidence-{i}-{tag}','question_sensitive_evidence',area,user+question,proposal,'retrieval',['S1','S2'])
            added[-1]['group_id']='calibration-evidence-family-'+str(i)
            added[-1]['document_hashes']=[hashlib.sha256(x.encode()).hexdigest() for x in (doc,distractor)]
    # Real reviewed abstracts supply hard negatives without a "no information"
    # phrase being planted in the passage. Their original provenance is retained.
    for i,row in enumerate(grouped.get('reviewed_academic_summary',[])):
        user=row['messages'][1]['content'];assert '\nQuestion: ' in user
        for j,question in enumerate(['What is the lead author\'s contact email address?','How much funding was allocated to this research?','At what time will the next public demonstration start?']):
            put(f'public-negative-{i}-{j}','public_abstract_abstention',row['scope'],user.split('\nQuestion: ')[0]+'\nQuestion: '+question,{'action':'answer','answer':'The supplied abstract does not provide that information.','citations':[],'abstain':True},'retrieval',row['source_ids'],{**row['provenance'],'negative_question_annotation':'Source inspected; requested contact, funding and future-event details are absent.'})
            added[-1]['group_id']=row['group_id'];added[-1]['document_hashes']=row.get('document_hashes',[])
            added[-1]['reviewed']=True;added[-1]['reviewer']='Codex source review of requested-detail absence; developer review, not blinded human review'
    rows+=added;random.Random(131).shuffle(rows);hashes={norm(r['messages'][1]['content']) for r in rows}
    assert len(hashes)==len(rows) and not hashes&excluded
    output.mkdir(parents=True);payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();(output/'train.jsonl').write_bytes(payload)
    manifest['calibration_rows_added']=len(added);manifest['replay_rows']=len(rows)-len(added);manifest['parent_manifest_sha256']=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest()
    manifest['splits']['train']={'rows':len(rows),'sha256':hashlib.sha256(payload).hexdigest(),'kinds':dict(Counter(r['kind'] for r in rows))}
    for split in ('validation','test'):
        raw=(source/(split+'.jsonl')).read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256']
        assert not hashes&{norm(json.loads(line)['messages'][1]['content']) for line in raw.decode().splitlines()}
        (output/(split+'.jsonl')).write_bytes(raw)
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'rows':len(rows),'new_rows':len(added),'replay':len(rows)-len(added),'exact_overlap_with_frozen_cases':0,'development_only':True}))

if __name__=='__main__':main()
