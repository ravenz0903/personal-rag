"""Build a portable T4 notebook containing only tooling and reviewed public data."""
import argparse
import base64
import hashlib
import io
import json
import zipfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);parser.add_argument('--approved')
    args=parser.parse_args();root=Path(__file__).resolve().parent;stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in ('train.py','prepare_data.py','adapter_profiles.py','legacy-contracts.json','align_runtime_data.py','challenge_cases.py','evaluate_challenge.py','contract.json','requirements.txt','dataset-review.md','test_prepare_data.py','test_challenge.py'):
            archive.writestr(name,(root/name).read_bytes())
        if args.approved:archive.writestr('approved-public.jsonl',Path(args.approved).read_bytes())
    raw=stream.getvalue();payload=base64.b64encode(raw).decode('ascii');sha=hashlib.sha256(raw).hexdigest()
    cells=[]
    def md(value):cells.append({'cell_type':'markdown','metadata':{},'source':value.splitlines(keepends=True)})
    def code(value):cells.append({'cell_type':'code','metadata':{},'execution_count':None,'outputs':[],'source':value.splitlines(keepends=True)})
    md('# Folio — Qwen3-4B T4 training\n\nSelect **Runtime → Change runtime type → T4 GPU**, then connect. The notebook checks the actual allocation. Training starts from the pinned base, uses FP16 on T4, masks prompt loss, rejects oversized examples, and saves checkpoints to your Drive. It does not enable Folio AI.\n\nThe default data is a synthetic pipeline pilot plus eight individually reviewed public summaries. It is not enough to establish real-user reliability. Public bulk datasets remain outside training. See the included dataset review. You authorize Colab/Drive to store this public/synthetic training package by running its cells; no personal Folio records or credentials are included.')
    code("import subprocess\nsubprocess.run(['nvidia-smi'],check=True)\n")
    code("%pip install -q transformers==4.57.1 peft==0.17.1 accelerate==1.10.1 bitsandbytes==0.48.1 'safetensors>=0.4.3'\n")
    code("import torch\nassert torch.cuda.is_available(), 'Choose T4 GPU before continuing'\nprint(torch.cuda.get_device_name(0), torch.cuda.get_device_properties(0).total_memory/1024**3, 'GiB')\nassert 'T4' in torch.cuda.get_device_name(0), 'This notebook was requested for T4. Change the runtime to T4.'\nprint('T4 training precision: FP16')\n")
    code(f"import base64, hashlib, io, zipfile\nfrom pathlib import Path\npayload=base64.b64decode('{payload}')\nassert hashlib.sha256(payload).hexdigest()=='{sha}'\nTOOLS=Path('/content/folio-training');TOOLS.mkdir(exist_ok=True)\nwith zipfile.ZipFile(io.BytesIO(payload)) as z:\n    for item in z.infolist():\n        destination=(TOOLS/item.filename).resolve()\n        assert destination.is_relative_to(TOOLS.resolve())\n        destination.write_bytes(z.read(item))\nprint('Extracted verified package:', sorted(p.name for p in TOOLS.iterdir()))\n")
    code("from google.colab import drive\ndrive.mount('/content/drive')\nfrom datetime import datetime, timezone\nRUN=Path('/content/drive/MyDrive/FolioTraining')/datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')\nRUN.mkdir(parents=True,exist_ok=False)\nprint('Checkpoints and reports:', RUN)\n")
    code("import subprocess\nsubprocess.run(['python','-m','unittest','discover','-s',str(TOOLS),'-p','test_*.py','-v'],check=True)\nRAW_DATA=RUN/'raw-data'\nDATA=RUN/'data'\ncommand=['python',str(TOOLS/'prepare_data.py'),'--output',str(RAW_DATA)]\nif (TOOLS/'approved-public.jsonl').exists():command+=['--approved',str(TOOLS/'approved-public.jsonl')]\nsubprocess.run(command,check=True)\nsubprocess.run(['python',str(TOOLS/'align_runtime_data.py'),'--input',str(RAW_DATA),'--output',str(DATA)],check=True)\n")
    md('## First run: small pipeline test\n\nThis trains eight optimizer steps and evaluates the base and adapter. Inspect the outputs and memory use before running the longer experiment. Loss and JSON scores do not prove factual accuracy.')
    code("subprocess.run(['python','-u',str(TOOLS/'train.py'),'--data',str(DATA),'--output',str(RUN/'smoke'),'--steps','8','--accumulation','4','--checkpoint-every','4'],check=True)\n")
    code("import json\nreport=json.loads((RUN/'smoke'/'experiment-report.json').read_text())\nprint(json.dumps({k:v for k,v in report.items() if k not in ('optimized_row_ids','losses')},indent=2))\nfor case in json.loads((RUN/'smoke'/'adapter-validation.json').read_text())['cases']:\n    if not case['structured_fields_match']:\n        print(case['kind'], case['output'], case['errors'])\n")
    md('## Longer experiment — run after inspecting the pilot\n\nThis is one pass over the current curated pilot mix, starting from the same original base. It remains development training. Add independently authored prompts and reviewed public examples before treating it as product qualification. Run this cell manually when ready.')
    code("rows=json.loads((DATA/'manifest.json').read_text())['splits']['train']['rows']\nsteps=(rows+3)//4\nsubprocess.run(['python','-u',str(TOOLS/'train.py'),'--data',str(DATA),'--output',str(RUN/'full'),'--steps',str(steps),'--accumulation','4','--checkpoint-every','25'],check=True)\n")
    md('## Recovering an interrupted run\n\nKeep this notebook and the printed RUN path. Reconnect T4 and repeat dependency/package setup. Do not create new data or delete old outputs. Use the same saved data and a fresh output folder with `--resume-checkpoint` pointing to the last completed checkpoint. Its manifest and optimizer state must match. Download the experiment report and adapter to continue local evaluation; do not enable it in Folio until factual, scope, approval, export, and runtime checks pass.')
    notebook={'nbformat':4,'nbformat_minor':5,'metadata':{'kernelspec':{'name':'python3','display_name':'Python 3'},'accelerator':'GPU','colab':{'name':'Folio_Qwen3_4B_T4.ipynb','gpuType':'T4'}},'cells':cells}
    path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(notebook,indent=2),encoding='utf-8')
    print(path)

if __name__=='__main__':main()
