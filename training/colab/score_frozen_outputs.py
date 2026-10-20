"""Re-score exact-runtime outputs using the same prospective content rules."""
import argparse
import hashlib
import json
import sys
from pathlib import Path
from evaluate_challenge import score


def main():
    sys.stdin.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser();parser.add_argument('--fixtures',required=True);args=parser.parse_args()
    folder=Path(args.fixtures);raw=(folder/'cases.jsonl').read_bytes();manifest=json.loads((folder/'manifest.json').read_text())
    assert hashlib.sha256(raw).hexdigest()==manifest['sha256']
    fixtures=[json.loads(x) for x in raw.decode().splitlines()];outputs=json.load(sys.stdin)
    assert len(outputs)==len(fixtures)==100 and len({r['id'] for r in outputs})==100
    indexed={r['id']:r for r in outputs};assert set(indexed)=={r['id'] for r in fixtures}
    results=[]
    for row in fixtures:
        result=indexed[row['id']];judged=score(row,result['output'])
        if result.get('finish_reason')!='stop':
            judged['passed']=False;judged['errors'].append('truncated or unfinished runtime output')
        results.append({**result,**judged})
    print(json.dumps({'fixtureSha256':manifest['sha256'],'passed':sum(r['passed'] for r in results),'unsafe':sum(r['unsafe_output'] for r in results),'total':100,'cases':results},ensure_ascii=True))


if __name__=='__main__':main()
