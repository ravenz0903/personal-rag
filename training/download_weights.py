"""Resumable, checksum-verified development download for the pinned pilot base."""
import hashlib
import argparse
import json
from pathlib import Path
import shutil
import time
import urllib.request

REVISION = '1cfa9a7208912126459214e8b04321603b3df60c'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--file', choices=[f'model-{i:05d}-of-00003.safetensors' for i in range(1, 4)])
    args = parser.parse_args()
    folder = Path(__file__).resolve().parent.parent / 'target/ai-training-cache/hub/models--Qwen--Qwen3-4B'
    snapshot = folder / 'snapshots' / REVISION
    snapshot.mkdir(parents=True, exist_ok=True)
    url = f'https://huggingface.co/api/models/Qwen/Qwen3-4B/tree/{REVISION}'
    with urllib.request.urlopen(url, timeout=30) as response:
        files = json.load(response)
    for entry in files:
        name = entry['path']
        if not name.endswith('.safetensors'):
            continue
        if args.file and name != args.file:
            continue
        if Path(name).name != name:
            raise ValueError('Unexpected model file path')
        size, sha = entry['size'], entry['lfs']['oid']
        destination = snapshot / name
        partial = snapshot / (name + '.partial')
        if not destination.exists():
            if not partial.exists():
                candidates = [p for p in (folder / 'blobs').glob(sha + '*.incomplete') if p.stat().st_size <= size]
                if candidates:
                    shutil.copyfile(max(candidates, key=lambda p: p.stat().st_size), partial)
            failures = 0
            while not partial.exists() or partial.stat().st_size < size:
                offset = partial.stat().st_size if partial.exists() else 0
                end = min(size - 1, offset + 64 * 1024 * 1024 - 1)
                request = urllib.request.Request(
                    f'https://huggingface.co/Qwen/Qwen3-4B/resolve/{REVISION}/{name}?download=true&offset={offset}',
                    headers={'Range': f'bytes={offset}-{end}'})
                try:
                    with urllib.request.urlopen(request, timeout=30) as response:
                        expected_range = f'bytes {offset}-{end}/{size}'
                        if response.status != 206 or response.headers.get('Content-Range') != expected_range:
                            raise ValueError('Server did not honor the exact requested byte range')
                        data = response.read(end - offset + 1)
                    if len(data) != end - offset + 1:
                        raise ValueError('Incomplete byte range')
                    with partial.open('ab') as output:
                        output.write(data)
                    failures = 0
                    print(json.dumps({'file': name, 'bytes': end + 1, 'total': size}), flush=True)
                except Exception as error:
                    failures += 1
                    # Do not print redirect URLs or transient signed download credentials.
                    print(json.dumps({'file': name, 'retry': failures, 'error_type': type(error).__name__}), flush=True)
                    if failures >= 5:
                        raise RuntimeError('Download failed repeatedly; partial data preserved') from None
                    time.sleep(1)
            candidate = partial
        else:
            candidate = destination
        digest = hashlib.sha256()
        with candidate.open('rb') as source:
            for chunk in iter(lambda: source.read(8 * 1024 * 1024), b''):
                digest.update(chunk)
        if candidate.stat().st_size != size or digest.hexdigest() != sha:
            raise ValueError('Model size/checksum mismatch: ' + name)
        if candidate == partial:
            partial.replace(destination)
        print(json.dumps({'file': name, 'sha256_verified': True}), flush=True)


if __name__ == '__main__':
    main()
