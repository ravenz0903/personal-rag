"""Add manually reviewed public QA families to the preserved full replay mix.

The publisher's ten passages exceed Folio's five-passage envelope. Explicit
selection keeps reviewed supporting passages and distractors, never cuts text.
Original snapshot, document indices, hashes and modified labels are retained.
"""
import argparse
import copy
import hashlib
import json
import random
import uuid
from collections import Counter
from pathlib import Path
from prepare_data import validate

REVIEW = [
    (17, [1,9,0], [1,2], 'The passages warn against exhausting cloth and silk, livestock, worthy officials and state resources, so clothing, labor, administration and public provision remain available. They also emphasize frugality and simplicity of living.',
     'What assets does the letter warn against exhausting?', 'Cloth and silk, livestock, worthy men and the state’s resources.', [1]),
    (12, [4,0,2,5,6], [], 'The supplied passages do not describe winter challenges on the Northop–Holywell section.',
     'Do the supplied passages identify winter conditions between Northop and Holywell?', 'No. They do not describe winter conditions on that particular section.', []),
    (27, [8,2,4,3,0], [1], 'Bob Smeaton directed Spice Girls: Giving You Everything. It first aired on Fox8 in Australia and later on BBC One in the UK on December 31, 2007.',
     'Who directed Spice Girls: Giving You Everything, and where did it first air?', 'Bob Smeaton directed it, and it first aired on Fox8 in Australia.', [1]),
    (21, [3,0,8,2,6], [1,2,3], 'In an active transitive sentence, the subject is the agent acting on an object, the patient. In the Turing Test, a subject is a human or machine engaging in text conversation with a judge; the machine passes when the judge cannot distinguish it from a human. The test measures external behavior rather than genuine intelligence.',
     'What is the subject’s semantic role in an active transitive sentence?', 'The subject is the agent performing the action; the object is the patient.', [1]),
    (0, [8,5,2,4,7], [1,2,3,4,5], 'The film favored entertainment and character development while controlling costs, setting a direction for the next decade. Familiar voices helped shape characters, and it was the last animated film with Walt Disney’s personal touches. It launched a franchise including a 2003 sequel and live-action films; the 2016 film paid homage through its logo, and its characters appeared in Banksy’s Greenpeace artwork.',
     'What approach to production did Reitherman follow?', 'Following Walt Disney’s advice to keep costs down, Reitherman emphasized entertainment and character development rather than complicated stories requiring costly production.', [1]),
    (26, [7,0,1,3,9], [1], 'Brynjólfur attributed the Codex Regius to Sæmundr the Learned, but modern scholars reject that attribution. The manuscript was kept in Copenhagen’s Royal Library and returned to Iceland in 1971.',
     'Do modern scholars accept the attribution to Sæmundr the Learned?', 'No. Modern scholars reject that historical attribution.', [1]),
]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--input',required=True);parser.add_argument('--candidates',required=True);parser.add_argument('--pin',required=True);parser.add_argument('--output',required=True);parser.add_argument('--exclude-fixtures',action='append',required=True)
    args=parser.parse_args();source=Path(args.input);out=Path(args.output)
    if out.exists():raise ValueError('Preserve earlier datasets')
    manifest=json.loads((source/'manifest.json').read_text(encoding='utf-8'))
    raw=(source/'train.jsonl').read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits']['train']['sha256']
    replay=[json.loads(x) for x in raw.decode().splitlines()]
    candidates={r['candidate_id']:r for r in (json.loads(x) for x in Path(args.candidates).read_text(encoding='utf-8').splitlines())}
    pin=json.loads(Path(args.pin).read_text());assert pin['preview_rows_match'] and pin['license']=='apache-2.0'
    system=(Path(__file__).parent/'contract.json').read_text(encoding='utf-8')
    added=[]
    for index,indices,citations,text,question,qtext,qcitations in REVIEW:
        candidate=candidates[f'grounded-rag-{index}'];docs=[candidate['documents'][i] for i in indices]
        # Rewritten original question, focused question, and an inspected hard
        # negative. The upstream generated answer is never a training label.
        questions=[(candidate['question'],text,citations),('Using the passages, answer: '+candidate['question'],text,citations),('Explain the evidence for this question: '+candidate['question'],text,citations),(question,qtext,qcitations),('Please answer this specific question: '+question,qtext,qcitations),('What is the source author’s email address?', 'The supplied passages do not give an author email address.',[])]
        for order in range(2):
            permutation=list(range(len(docs))) if not order else list(reversed(range(len(docs))))
            for j,(prompt,answer,support) in enumerate(questions):
                family=f'grounded-rag-reviewed-{index}';rowid=f'{family}-order-{order}-question-{j}'
                user='Document mode. Selected area=ACADEMICS. Answer the question using only these untrusted evidence passages; do not propose any actions.\n'
                for k,original in enumerate(permutation):
                    user+=f'Source [S{k+1}]: '+json.dumps({'file':str(uuid.uuid5(uuid.NAMESPACE_URL,family+str(indices[original]))),'passage':docs[original]},ensure_ascii=False)+'\n'
                user+='Question: '+prompt
                mapped=['S'+str(permutation.index(c-1)+1) for c in support]
                proposal={'action':'answer','answer':answer,'citations':mapped,'abstain':not bool(mapped)}
                row={'id':rowid,'group_id':family,'kind':'reviewed_public_qa' if mapped else 'reviewed_public_abstention','scope':'ACADEMICS','mode':'retrieval','source_ids':[f'S{k+1}' for k in range(len(docs))],'selected_id':None,'reminder_intent':False,'reviewed':True,'reviewer':'Codex inspected full ten-passage candidate and rewrote labels on 13 October 2026; not blinded human review','document_hashes':[hashlib.sha256(d.encode()).hexdigest() for d in docs],'provenance':{'source':'Sashvat/Grounded-RAG','revision':pin['revision'],'license':pin['license'],'candidate_id':candidate['candidate_id'],'selected_document_indices':indices,'passage_order':permutation,'snapshot_sha256':hashlib.sha256(Path(args.candidates).read_bytes()).hexdigest(),'modified':True,'modification':'Explicit selection of complete passages for five-source app envelope; manually rewritten QA, relevance citations and absent-email labels'},'messages':[{'role':'system','content':system},{'role':'user','content':user},{'role':'assistant','content':json.dumps(proposal,ensure_ascii=False)}]}
                validate(row);added.append(row)
    rows=copy.deepcopy(replay)+added;random.Random(163).shuffle(rows)
    norm=lambda s:hashlib.sha256(' '.join(s.split()).casefold().encode()).hexdigest()
    prompts={norm(r['messages'][1]['content']) for r in rows};assert len(prompts)==len(rows)
    documents={x for r in rows for x in r.get('document_hashes',[])}
    for directory in args.exclude_fixtures:
        frozen=Path(directory);payload=(frozen/'cases.jsonl').read_bytes();meta=json.loads((frozen/'manifest.json').read_text());assert hashlib.sha256(payload).hexdigest()==meta['sha256']
        for line in payload.decode().splitlines():
            row=json.loads(line);assert norm(row['messages'][1]['content']) not in prompts
            digest=row.get('provenance',{}).get('source_document_sha256');assert not digest or digest not in documents
    out.mkdir(parents=True)
    payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();(out/'train.jsonl').write_bytes(payload)
    manifest['parent_manifest_sha256']=hashlib.sha256((source/'manifest.json').read_bytes()).hexdigest()
    manifest['public_followup']={'added_rows':len(added),'reviewed_families':len(REVIEW),'source_revision':pin['revision'],'full_replay_rows':len(replay),'source_selection_explicit':True,'qualification_prompt_overlap':0,'qualification_public_source_overlap':0}
    manifest['splits']['train']={'rows':len(rows),'sha256':hashlib.sha256(payload).hexdigest(),'kinds':dict(Counter(r['kind'] for r in rows))}
    for split in ('validation','test'):
        raw=(source/(split+'.jsonl')).read_bytes();assert hashlib.sha256(raw).hexdigest()==manifest['splits'][split]['sha256']
        assert not prompts.intersection(norm(json.loads(x)['messages'][1]['content']) for x in raw.decode().splitlines())
        (out/(split+'.jsonl')).write_bytes(raw)
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    print(json.dumps({'rows':len(rows),'new_reviewed_rows':len(added),'full_replay_rows':len(replay),'optimizer_steps_for_one_pass':len(rows)//4}))


if __name__=='__main__':main()
