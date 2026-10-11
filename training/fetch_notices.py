"""Preserve publisher notices alongside private model/runtime build assets."""
import hashlib, json, urllib.request
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
BASE = '1cfa9a7208912126459214e8b04321603b3df60c'
CPP = 'd81235049384534c167caea52b85a694f6103d14'
def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()
def main():
    folder = ROOT/'target/ai-notices'; folder.mkdir(exist_ok=True)
    sources = {
        'Qwen3-4B-LICENSE.txt': f'https://huggingface.co/Qwen/Qwen3-4B/resolve/{BASE}/LICENSE',
        'Qwen3-4B-MODEL-CARD.md': f'https://huggingface.co/Qwen/Qwen3-4B/resolve/{BASE}/README.md',
        'llama.cpp-LICENSE.txt': f'https://raw.githubusercontent.com/ggml-org/llama.cpp/{CPP}/LICENSE',
        'nomic-embedding-MODEL-CARD.md': 'https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/0188c9bf409793f810680a5a431e7b899c46104c/README.md'
    }
    # The pinned GGUF card declares Apache-2.0 but its repository has no LICENSE file.
    sources['nomic-embedding-Apache-2.0.txt'] = 'https://www.apache.org/licenses/LICENSE-2.0.txt'
    report = []
    for name, url in sources.items():
        data = fetch(url); (folder/name).write_bytes(data)
        report.append({'path': name, 'url': url, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        print(name, flush=True)
    (folder/'sources.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    (folder/'FOLIO-MODEL-NOTICE.txt').write_text(
        'Folio private beta uses Qwen3-4B, developed by the Qwen team, under Apache-2.0.\n'
        'The chat model is modified by a Folio-specific LoRA adapter, merged and quantized to Q4_K_M.\n'
        'nomic-embed-text-v1.5 was developed by Nomic AI; the pinned Q8_0 GGUF is used unchanged.\n'
        'llama.cpp was developed by the ggml-org contributors under the MIT license.\n'
        'Publisher license texts and model cards are included in this directory.\n'
        'Fine-tuning scores are finite synthetic test results, not an accuracy guarantee.\n',encoding='utf-8')
if __name__ == '__main__': main()
