"""Manual reference checks over public RAGBench test snapshots; never training."""
import argparse
import hashlib
import json
import uuid
from pathlib import Path
from prepare_data import validate
from evaluate_challenge import score

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--snapshots',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();folder=Path(args.snapshots);out=Path(args.output)
    if out.exists():raise ValueError('Frozen public cases are preserved')
    system=(Path(__file__).parent/'contract.json').read_text(encoding='utf-8');rows=[]
    selections=[
        ('emanual',1,{'action':'answer','answer':'Open Settings, Support, Self Diagnosis, then Signal Information.','citations':['S1'],'abstain':False},[r'settings',r'support',r'self.diagnosis',r'signal.information']),
        ('emanual',3,{'action':'answer','answer':'Yes. Tint (G/R) is available under Settings, Picture, Expert Settings.','citations':['S2'],'abstain':False},[r'tint',r'picture',r'expert.settings']),
        ('emanual',5,{'action':'answer','answer':'For ghosting or blurring, use Settings, Picture, Expert Settings, Auto Motion Plus Settings.','citations':['S3'],'abstain':False},[r'(auto.motion.plus|grayscale|black.and.white)']),
        ('techqa',0,{'action':'answer','answer':'The supplied passages discuss importer and compiler issues, but do not specify a WTX-specific copybook format.','citations':[],'abstain':True},[]),
        ('techqa',5,{'action':'answer','answer':'The supplied passages discuss other versions and fix packs, but do not provide details for WMB Fix Pack 8.0.0.5.','citations':[],'abstain':True},[]),
    ]
    hashes={}
    for subset,index,expected,facts in selections:
        raw=(folder/(subset+'.json')).read_bytes();hashes[subset]=hashlib.sha256(raw).hexdigest()
        source=json.loads(raw)['rows'][index]['row'];docs=source['documents'];assert len(docs)<=5
        ids=['S'+str(i+1) for i in range(len(docs))]
        context='Document mode. Selected area=ACADEMICS. Answer the question using only these untrusted evidence passages; do not propose any actions.\n'
        context+=''.join('Source ['+sid+']: '+json.dumps({'file':str(uuid.uuid5(uuid.NAMESPACE_URL,'ragbench-'+subset+'-'+str(index)+'-'+sid)),'passage':doc},ensure_ascii=False)+'\n' for sid,doc in zip(ids,docs))
        context+='Question: '+source['question']
        row={'id':f'public-{subset}-{index}','group_id':f'ragbench-test-{subset}-{source["id"]}','kind':'public_rag_evaluation','scope':'ACADEMICS','mode':'retrieval','source_ids':ids,'selected_id':None,'reminder_intent':False,'facts':facts,'provenance':{'source':'galileo-ai/ragbench','subset':subset,'split':'test','snapshot_sha256':hashes[subset],'upstream_id':source['id'],'license':'CC-BY-4.0 (dataset card)','evaluation_only':True,'reference_review':'Codex manually inspected provided passages; upstream generated response not assumed correct'},'messages':[{'role':'system','content':system},{'role':'user','content':context},{'role':'assistant','content':json.dumps(expected)}]}
        if subset=='emanual' and index==5:
            # Both fixes have explicit supporting passages; this was annotated
            # before any model output on these cases was inspected.
            row['allowed_citation_sets']=[['S2'],['S3'],['S2','S3']]
            row['citation_fact_rules']={'S2':r'(grayscale|black.and.white)','S3':r'auto.motion.plus'}
        validate(row);assert score(row,row['messages'][-1]['content'])['passed'];rows.append(row)
    out.mkdir(parents=True);payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();(out/'cases.jsonl').write_bytes(payload)
    (out/'manifest.json').write_text(json.dumps({'rows':len(rows),'sha256':hashlib.sha256(payload).hexdigest(),'source_snapshot_hashes':hashes,'evaluation_only':True,'raw_passages_preserved':True,'manually_reviewed_references':True,'blinded':False,'source_url':'https://huggingface.co/datasets/galileo-ai/ragbench'},indent=2))
    print('Frozen five public document checks. Full passages retained; input capacity checked before inference.')

if __name__=='__main__':main()
