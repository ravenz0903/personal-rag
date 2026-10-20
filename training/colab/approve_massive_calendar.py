"""Manually relabel a bounded MASSIVE calendar-language review for Folio.

The calendar_set intent is not a Folio action. Incomplete requests clarify;
external, conditional and recurring operations stay unsupported. Complete event
variants add explicit user details and retain modification provenance.
"""
import argparse
import hashlib
import json
from pathlib import Path
from prepare_data import validate

CLARIFY = {
    '3601':'Which date and task should this reminder be for?',
    '6511':'What reminder would you like to create?',
    '6730':'What exact start and end times should I use for the chat?',
    '6756':'Which date and exact time next week should I use?',
    '6757':'What exact reminder time should I use tomorrow?',
    '6763':'Which date and exact time should I remind you?',
    '6765':'Which date and exact time should I remind you to file the invoice?',
    '6770':'Which date, start time and end time should the meeting use?',
    '6772':'What title, date, start time and end time should the event have?',
    '6773':'What title, date, start time and end time should the new event have?',
    '6787':'What end time should I use for the meeting tomorrow?',
    '6788':'What exact date, start time and end time should the meeting use?',
    '6803':'What exact reminder time should I use on Sunday?',
    '6809':'Which exact date, start time and end time should the wedding event use?',
    '6813':'What exact reminder time should I use on Thursday?',
    '6814':'What is the birthday date and the precise reminder time?',
    '6816':'Which date should I use for the 16:00 reminder?',
    '6817':'When do the shops close, and what exact date and time should I remind you?',
    '6818':'When do the shops close, and what exact date and time should I remind you?',
    '6820':'Would you like a calendar event, and what exact start and end times should it have?',
    '6822':'Which exact date and reminder time should I use this weekend?',
    '6832':'Which date, AM/PM choices and exact reminder time should I use?',
    '6833':'What event title and AM/PM choice should I use for tomorrow’s reminder?',
}
UNSUPPORTED = {
    '3201':'Conditional monitoring is not supported by Folio proposals.',
    '5945':'Folio cannot send reminder notifications to your friend.',
    '6418':'Opening memos or adding note content is not supported by this proposal schema.',
    '6742':'Recurring calendar meetings are not supported by this proposal schema.',
    '6743':'Recurring calendar calls are not supported by this proposal schema.',
    '6745':'Recurring calendar calls are not supported by this proposal schema.',
    '6766':'Recurring reminders are not represented by this proposal schema.',
    '6767':'Recurring reminders are not represented by this proposal schema.',
    '6768':'Recurring reminders are not represented by this proposal schema.',
    '6804':'Recurring reminders are not represented by this proposal schema.',
    '6824':'Recurring calendar events are not supported by this proposal schema.',
    '6825':'Recurring reminders are not represented by this proposal schema.',
    '6826':'Recurring calendar events are not supported by this proposal schema.',
}
REMINDERS={'6758':('watch new movie','2046-09-02T21:00'),'6810':('gym class','2046-09-02T10:30'),'6811':('gym class','2046-09-02T10:30'),'6812':('look at the pasta','2046-09-02T09:00')}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidates',required=True);parser.add_argument('--metadata',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();out=Path(args.output)
    if out.exists():raise ValueError('Preserve approval snapshots')
    metadata=json.loads(Path(args.metadata).read_text())
    licenses=[metadata['license']] if isinstance(metadata['license'],str) else metadata['license']
    assert licenses==['cc-by-4.0']
    candidates=[json.loads(x) for x in Path(args.candidates).read_text(encoding='utf-8').splitlines()];pool={str(r['id']):r for r in candidates}
    assert set(pool)==set(CLARIFY)|set(UNSUPPORTED)|set(REMINDERS)
    system=(Path(__file__).parent/'contract.json').read_text(encoding='utf-8');rows=[]
    def add(source,tag,request,proposal,*,dates=(),clocks=(),area='ACADEMICS',modification=None):
        fields=[proposal[k] for k in ('dueLocal','remindLocal','startLocal','endLocal') if k in proposal]
        dates=list(dates) or sorted({x[:10] for x in fields});clocks=list(clocks) or sorted({x[11:] for x in fields})
        user=f'Selected area={area}. Workspace timezone=Asia/Kolkata. Current time=2046-09-02T08:30+05:30[Asia/Kolkata]. Resolved dates=[{", ".join(dates)}]. Explicit clock times=[{", ".join(clocks)}]. No task is selected. Edits require selection.\nUser request: '+request
        sid=str(source['id']);row={'id':f'massive-reviewed-{sid}-{tag}','group_id':'massive-en-US-train-'+sid,'kind':'reviewed_calendar_language','scope':area,'mode':'actions','source_ids':[],'selected_id':None,'reminder_intent':'remindLocal' in proposal,'reviewed':True,'reviewer':'Codex source-by-source Folio intent and missing-field review, 13 October 2026','provenance':{'source':'AmazonScience/massive','revision':metadata['revision'],'archive_sha256':metadata['archive_sha256'],'locale_sha256':metadata['locale_sha256'],'license':'CC-BY-4.0','partition':'train','upstream_id':sid,'original_utterance':source['utt'],'original_intent':source['intent'],'modified':True,'modification':modification or 'Added trusted Folio envelope; manually relabeled into current proposal schema; upstream intent is not a proposal label'},'messages':[{'role':'system','content':system},{'role':'user','content':user},{'role':'assistant','content':json.dumps(proposal,ensure_ascii=False)}]}
        validate(row);rows.append(row)
    for sid,text in CLARIFY.items():
        dates=['2046-09-03'] if sid in ('6757','6787','6833') else []
        clocks=['18:00'] if sid=='6787' else ['16:00'] if sid=='6816' else []
        add(pool[sid],'original',pool[sid]['utt'],{'action':'clarify','answer':text},dates=dates,clocks=clocks)
    for sid,text in UNSUPPORTED.items():add(pool[sid],'original',pool[sid]['utt'],{'action':'unsupported','answer':text})
    for sid,(title,stamp) in REMINDERS.items():add(pool[sid],'original',pool[sid]['utt'],{'action':'create_task','area':'ACADEMICS','title':title,'remindLocal':stamp})
    variants=[
        ('6787','meeting with paul','2046-09-03T18:00','2046-09-03T18:45','ACADEMICS','create meeting with paul tomorrow at six p. m. Put it in Folio’s local calendar with the exact title "meeting with paul" and an end time of 18:45.'),
        ('6770','meeting with hongbin','2046-09-05T11:20','2046-09-05T12:10','CAREER','set a meeting with hongbin in my local calendar. Use title "meeting with hongbin", September 5, 2046 from 11:20 until 12:10.'),
        ('6730','chat with adam','2046-09-06T14:10','2046-09-06T14:50','CAREER','schedule a chat with adam in Folio’s local calendar. Exact title "chat with adam", exact date September 6, 2046, start 14:10 and finish 14:50.'),
        ('6809',"lily's wedding",'2046-09-12T15:00','2046-09-12T17:30','PERSONAL','please add lily’s wedding to my local calendar, with exact title "lily\'s wedding". Use September 12, 2046, 15:00 to 17:30.'),
        ('6772','Gallery tour','2046-09-08T10:25','2046-09-08T11:05','PERSONAL','add an event to my calendar. Its title is "Gallery tour", date September 8, 2046, start 10:25, end 11:05.'),
        ('6773','Project demo','2046-09-09T16:40','2046-09-09T17:20','CAREER','add a new event to my calendar named "Project demo" on September 9, 2046 from 16:40 to 17:20.'),
    ]
    for sid,title,start,end,area,request in variants:
        add(pool[sid],'complete-event',request,{'action':'create_event','area':area,'title':title,'startLocal':start,'endLocal':end},area=area,modification='Adapted reviewed utterance phrasing with explicit title, date, start/end times and local-calendar destination; no default duration inferred')
    payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();out.write_bytes(payload)
    out.with_suffix('.manifest.json').write_text(json.dumps({'rows':len(rows),'original_requests':len(candidates),'complete_event_variants':len(variants),'sha256':hashlib.sha256(payload).hexdigest(),'reviewed':True,'used_for_training':False,'source':'AmazonScience/massive','revision':metadata['revision'],'license':'CC-BY-4.0'},indent=2))
    print(json.dumps({'reviewed_rows':len(rows),'original_requests':40,'complete_event_variants':6,'used_for_training':False}))


if __name__=='__main__':main()
