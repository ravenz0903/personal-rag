"""Fetch only pinned official runtime artifacts; verify before extracting."""
import hashlib,json,zipfile,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent

def main():
    lock=json.loads((ROOT/'desktop/ai/runtime-lock.json').read_text())
    folder=ROOT/'target/ai-runtime'/lock['revision'];folder.mkdir(parents=True,exist_ok=True)
    for asset in lock['artifacts']:
        archive=folder/(asset['backend']+'.zip')
        if not archive.exists():
            partial=archive.with_suffix('.partial')
            with urllib.request.urlopen(asset['url'],timeout=90) as response,partial.open('wb') as out:
                while chunk:=response.read(1024*1024):out.write(chunk)
            partial.replace(archive)
        if archive.stat().st_size!=asset['bytes'] or hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=asset['sha256']:
            raise ValueError('Runtime checksum mismatch: '+asset['backend'])
        destination=folder/asset['backend'];destination.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                path=Path(item.filename)
                if path.is_absolute() or '..' in path.parts or '\\' in item.filename or ':' in item.filename:
                    raise ValueError('Unsafe runtime archive')
                source.extract(item,destination)
        print(json.dumps({'backend':asset['backend'],'revision':lock['revision'],'verified':True,'directory':str(destination)}),flush=True)

if __name__=='__main__':main()
