"""Development-only resumable download of the publisher's pinned embedding GGUF."""
import hashlib,json,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
def main():
    lock=json.loads((ROOT/'desktop/ai/embedding-lock.json').read_text())
    folder=ROOT/'target/ai-embedding';folder.mkdir(exist_ok=True)
    file=folder/lock['file'];partial=file.with_suffix(file.suffix+'.partial')
    failures=0
    while not file.exists() and (not partial.exists() or partial.stat().st_size<lock['bytes']):
        offset=partial.stat().st_size if partial.exists() else 0;end=min(lock['bytes']-1,offset+8*1024**2-1)
        req=urllib.request.Request(f"https://huggingface.co/{lock['repo']}/resolve/{lock['revision']}/{lock['file']}?download=true&offset={offset}",headers={'Range':f'bytes={offset}-{end}'})
        try:
            with urllib.request.urlopen(req,timeout=30) as response:
                if response.status!=206 or response.headers.get('Content-Range')!=f"bytes {offset}-{end}/{lock['bytes']}":raise ValueError('Range not honored')
                data=response.read(end-offset+1)
            if len(data)!=end-offset+1:raise ValueError('Incomplete range')
            with partial.open('ab') as output:output.write(data)
            failures=0;print(json.dumps({'bytes':end+1,'total':lock['bytes']}),flush=True)
        except Exception as error:
            failures+=1;print(json.dumps({'retry':failures,'errorType':type(error).__name__}),flush=True)
            if failures>=5:raise RuntimeError('Download stopped; partial data preserved') from None
            time.sleep(1)
    candidate=file if file.exists() else partial;hash_=hashlib.sha256()
    with candidate.open('rb') as source:
        for block in iter(lambda:source.read(8*1024**2),b''):hash_.update(block)
    if candidate.stat().st_size!=lock['bytes'] or hash_.hexdigest()!=lock['sha256']:raise ValueError('Publisher checksum mismatch')
    if candidate==partial:partial.replace(file)
    print(json.dumps({'verified':True,'revision':lock['revision'],'sha256':lock['sha256']}),flush=True)
if __name__=='__main__':main()
