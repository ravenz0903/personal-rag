"""Download bounded public candidate previews, never auto-approve their answers."""
import argparse
import hashlib
import json
import urllib.parse
import urllib.request
from pathlib import Path

SCITLDR_REVISION='5ccad9c00a60ad75c9e04abf7f27d0f53f983b20'

def get(url):
    request=urllib.request.Request(url,headers={'User-Agent':'Folio-public-dataset-review'})
    with urllib.request.urlopen(request,timeout=60) as response:return response.read()

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);parser.add_argument('--limit',type=int,default=30)
    args=parser.parse_args();assert 1<=args.limit<=100
    output=Path(args.output)
    if output.exists():raise ValueError('Preserve old candidate snapshots; choose a new directory')
    output.mkdir(parents=True)
    failures={}
    for source in ('scitldr','grounded-rag'):
        try:
            if source=='scitldr':
                url=f'https://raw.githubusercontent.com/allenai/scitldr/{SCITLDR_REVISION}/SciTLDR-Data/SciTLDR-A/train.jsonl'
                raw=get(url);rows=[json.loads(line) for line in raw.decode().splitlines()][:args.limit]
                revision=SCITLDR_REVISION;license_='Apache-2.0 (original repository)'
            else:
                params=urllib.parse.urlencode({'dataset':'Sashvat/Grounded-RAG','config':'default','split':'train','offset':0,'length':args.limit})
                url='https://datasets-server.huggingface.co/rows?'+params
                raw=get(url);rows=[x['row'] for x in json.loads(raw)['rows']]
                revision='preview snapshot; resolve source commit before approval';license_='Apache-2.0 (dataset card)'
            (output/(source+'.json')).write_bytes(raw)
            review=[]
            for i,row in enumerate(rows):
                if source=='scitldr':
                    documents=[' '.join(row['source'])];question='Summarize the supplied research abstract.';answer=row['target'][0]
                else:
                    documents=row['documents'];question=row['question'];answer=row['answer']
                review.append({'candidate_id':source+'-'+str(i),'source':source,'revision':revision,'license':license_,'reviewed':False,'question':question,'documents':documents,'document_hashes':[hashlib.sha256(x.encode()).hexdigest() for x in documents],'candidate_answer':answer,'citation_annotation_required':True,'upstream_id':row.get('paper_id')})
            payload=''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in review).encode()
            (output/(source+'-review.jsonl')).write_bytes(payload)
            (output/(source+'-provenance.json')).write_text(json.dumps({'url':url,'revision':revision,'download_sha256':hashlib.sha256(raw).hexdigest(),'review_rows':len(review),'approved_rows':0,'license':license_},indent=2),encoding='utf-8')
            print(source+': '+str(len(review))+' unapproved candidates',flush=True)
        except Exception as error:
            failures[source]=str(error);print(source+': '+str(error),flush=True)
    if failures:(output/'failures.json').write_text(json.dumps(failures,indent=2),encoding='utf-8')

if __name__=='__main__':main()
