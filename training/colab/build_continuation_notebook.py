"""Self-contained T4 tooling; imports a separately verified local adapter ZIP."""
import argparse
import base64
import hashlib
import io
import json
import zipfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--transfer',required=True);parser.add_argument('--output',required=True);parser.add_argument('--drive-transfer',action='store_true',help='Read the exact transfer ZIP from mounted My Drive')
    parser.add_argument('--local-session',action='store_true',help='Use direct upload and downloadable session results without mounting Drive')
    args=parser.parse_args();root=Path(__file__).resolve().parent;transfer=Path(args.transfer);stream=io.BytesIO()
    assert not (args.local_session and args.drive_transfer)
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as archive:
        for name in ('train.py','prepare_data.py','adapter_profiles.py','legacy-contracts.json','contract.json','requirements.txt','dataset-review.md','evaluate_challenge.py','challenge_cases.py'):
            archive.writestr(name,(root/name).read_bytes())
        fixtures=root.parent.parent/'target'/'folio-challenge-frozen-v2'
        frozen=(fixtures/'cases.jsonl').read_bytes()
        metadata=json.loads((fixtures/'manifest.json').read_text())
        assert hashlib.sha256(frozen).hexdigest()==metadata['sha256']
        for name in ('cases.jsonl','manifest.json'):
            archive.writestr('development-cases/'+name,(fixtures/name).read_bytes())
    raw=stream.getvalue();encoded=base64.b64encode(raw).decode();tool_sha=hashlib.sha256(raw).hexdigest();transfer_sha=hashlib.sha256(transfer.read_bytes()).hexdigest()
    cells=[]
    def md(text):cells.append({'cell_type':'markdown','metadata':{},'source':text.splitlines(keepends=True)})
    def code(text):cells.append({'cell_type':'code','metadata':{},'execution_count':None,'outputs':[],'source':text.splitlines(keepends=True)})
    transfer_instruction=('Allow Drive mounting when prompted; the notebook reads **'+transfer.name+'** from My Drive.' if args.drive_transfer else 'Upload the separately supplied **'+transfer.name+'** when prompted.')
    md('# Continue Folio local training on T4\n\nChoose **Runtime → Change runtime type → T4 GPU**, connect, and run the setup cells. '+transfer_instruction+' This notebook imports the local development adapter and restarts the optimizer for T4. It does not claim exact recovery of the Windows/BF16 run or enable Folio AI.\n\nThe data remains mostly synthetic development data with eight reviewed public summaries. Evaluate on T4 before further training. Synthetic JSON scores do not establish general factual accuracy.')
    code("import subprocess\nsubprocess.run(['nvidia-smi'],check=True)\n")
    code("%pip install -q transformers==4.57.1 peft==0.17.1 accelerate==1.10.1 bitsandbytes==0.48.1 'safetensors>=0.4.3'\n")
    code("import torch\nassert torch.cuda.is_available() and 'T4' in torch.cuda.get_device_name(0), 'Select and connect a T4 GPU'\nprint(torch.cuda.get_device_name(0), 'FP16 training on T4')\n")
    code(f"import base64,hashlib,io,json,zipfile\nfrom pathlib import Path\nTOOLS=Path('/content/folio-tools');TOOLS.mkdir(exist_ok=True)\npayload=base64.b64decode('{encoded}')\nassert hashlib.sha256(payload).hexdigest()=='{tool_sha}'\nwith zipfile.ZipFile(io.BytesIO(payload)) as z:\n    for info in z.infolist():\n        destination=(TOOLS/info.filename).resolve()\n        assert destination.is_relative_to(TOOLS.resolve())\n        destination.parent.mkdir(parents=True,exist_ok=True)\n        destination.write_bytes(z.read(info))\n")
    import_source=("from google.colab import drive\ndrive.mount('/content/drive')\nTRANSFER=Path('/content/drive/MyDrive')/"+repr(transfer.name)+"\npayload=TRANSFER.read_bytes()\n" if args.drive_transfer else "from google.colab import files\nupload=files.upload()\nassert len(upload)==1, 'Upload the adapter transfer ZIP only'\nname,payload=next(iter(upload.items()))\n")
    code(f"{import_source}assert hashlib.sha256(payload).hexdigest()=='{transfer_sha}', 'Transfer ZIP checksum mismatch'\nIMPORT=Path('/content/folio-import');IMPORT.mkdir(exist_ok=False)\nwith zipfile.ZipFile(io.BytesIO(payload)) as z:\n    manifest=json.loads(z.read('transfer-manifest.json'))\n    assert manifest['model']=='Qwen/Qwen3-4B' and manifest['revision']=='1cfa9a7208912126459214e8b04321603b3df60c'\n    for member,spec in manifest['files'].items():\n        data=z.read(member)\n        assert len(data)==spec['bytes'] and hashlib.sha256(data).hexdigest()==spec['sha256']\n        destination=(IMPORT/member).resolve()\n        assert destination.is_relative_to(IMPORT.resolve())\n        destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data)\nprint('Verified adapter and data imported; optimizer will restart')\n")
    if args.local_session:
        md("## Session storage\n\nDrive mounting is disabled. Reports/checkpoints are stored only in this Colab runtime. Download results before disconnecting; a runtime reset can erase them.")
        code("from datetime import datetime,timezone\nRUN=Path('/content/FolioTraining')/('transfer-'+datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S'))\nRUN.mkdir(parents=True,exist_ok=False)\nDATA=IMPORT/'imported/data'\nPARENT=IMPORT/'imported/adapter'\nprint('Temporary reports/checkpoints:',RUN)\n")
    else:
        code("from google.colab import drive\nif not Path('/content/drive/MyDrive').is_dir():\n    drive.mount('/content/drive')\nfrom datetime import datetime,timezone\nRUN=Path('/content/drive/MyDrive/FolioTraining')/('transfer-'+datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S'))\nRUN.mkdir(parents=True,exist_ok=False)\nDATA=IMPORT/'imported/data'\nPARENT=IMPORT/'imported/adapter'\nprint('New reports/checkpoints:',RUN)\n")
    md('## Evaluate the imported adapter first\n\nThis generates a fresh T4 base comparison and imported-adapter outputs on the saved development validation/test cases. No training updates occur. Review the generated JSON and answers before continuing.')
    code("subprocess.run(['python','-u',str(TOOLS/'train.py'),'--data',str(DATA),'--output',str(RUN/'preflight'),'--parent-adapter',str(PARENT),'--evaluate-only'],check=True)\nreport=json.loads((RUN/'preflight/experiment-report.json').read_text())\nprint(json.dumps({k:v for k,v in report.items() if k not in ('losses','optimized_row_ids')},indent=2))\n")
    md('## Separate development comparison on T4\n\nThese 24 frozen cases stay outside training. Compare the base and imported adapter on this T4; local GPU results are not reused. Read the actual answers as well as strict scores. These are developer cases, not blind release qualification.')
    code("from huggingface_hub import snapshot_download\nBASE=snapshot_download('Qwen/Qwen3-4B',revision='1cfa9a7208912126459214e8b04321603b3df60c',local_files_only=True)\nsubprocess.run(['python','-u',str(TOOLS/'evaluate_challenge.py'),'--adapter',str(PARENT),'--base-path',BASE,'--training-data',str(DATA),'--fixtures',str(TOOLS/'development-cases'),'--output',str(RUN/'development-preflight')],check=True)\ncomparison=json.loads((RUN/'development-preflight/report.json').read_text())\nprint(json.dumps({k:v for k,v in comparison.items() if k!='cases'},indent=2))\n")
    md('## Continue training — run manually after reviewing the T4 results\n\nOne pass over the imported data starts a new optimizer. Checkpoints save every 25 steps. In session-storage mode, download them before disconnecting; otherwise they save to Drive. Further qualification needs independently authored prompts and source-disjoint documents; do not train on evaluation labels.')
    code("rows=json.loads((DATA/'manifest.json').read_text())['splits']['train']['rows']\nsteps=(rows+3)//4\nsubprocess.run(['python','-u',str(TOOLS/'train.py'),'--data',str(DATA),'--output',str(RUN/'continued'),'--parent-adapter',str(PARENT),'--baseline-from',str(RUN/'preflight'),'--steps',str(steps),'--accumulation','4','--checkpoint-every','25','--learning-rate','0.00002'],check=True)\n")
    if args.local_session:
        md('## Download results before disconnecting\n\nRun this after evaluations, and again after any further training. It exports the current run including checkpoints. Keep the download to recover your work.')
        code("import shutil\nfrom google.colab import files\narchive=shutil.make_archive(str(RUN)+'-results','zip',root_dir=RUN)\nfiles.download(archive)\n")
    md('Save the adapter and experiment reports to continue local checks. The package and notebook have been checked locally; this notebook has not yet executed on Colab. The new T4 comparison is necessary because precision and library versions differ from the local run.')
    notebook={'nbformat':4,'nbformat_minor':5,'metadata':{'kernelspec':{'name':'python3','display_name':'Python 3'},'accelerator':'GPU','colab':{'name':'Folio_Continue_Local_Adapter_T4.ipynb','gpuType':'T4'}},'cells':cells}
    path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(notebook,indent=2),encoding='utf-8');print(path)

if __name__=='__main__':main()
