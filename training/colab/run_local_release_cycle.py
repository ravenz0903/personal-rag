"""Sequential local GPU training and evaluations; never automatically deploys.

Each subprocess has its own retained log. A failure stops the cycle. Completion
of this cycle is development evidence, not an export or release approval.
"""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path
from train import write_json


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='target/folio-grounded-adapter-v16');parser.add_argument('--data',default='target/folio-grounded-data-v16');parser.add_argument('--parent',default='target/folio-clock-full-v15');parser.add_argument('--fixtures',default='target/folio-release-frozen-v3')
    parser.add_argument('--learning-rate',type=float,default=0.000015)
    parser.add_argument('--adapter-profile',choices=('attention','full-linear'),default='attention')
    parser.add_argument('--previous-qualification-fixtures')
    parser.add_argument('--previous-qualification-base-report')
    args=parser.parse_args();root=Path(__file__).resolve().parents[2];os.chdir(root)
    state=Path(args.output+'-cycle.json')
    if state.exists() or Path(args.output).exists():raise ValueError('A cycle must use fresh output paths')
    base='target/ai-training-cache/hub/models--Qwen--Qwen3-4B/snapshots/1cfa9a7208912126459214e8b04321603b3df60c'
    manifest=json.loads((Path(args.data)/'manifest.json').read_text())
    count=manifest['splits']['train']['rows'];assert count%4==0
    jobs=[('training',['training/colab/train.py','--data',args.data,'--output',args.output,'--base-path',base,'--parent-adapter',args.parent+'/adapter','--baseline-from',args.parent,'--steps',str(count//4),'--accumulation','4','--learning-rate',str(args.learning_rate),'--max-length','1536','--checkpoint-every','25'])]
    jobs[0][1].extend(['--adapter-profile',args.adapter_profile])
    suffix=Path(args.output).name
    evaluations=[('qualification',args.fixtures,None,False),('regression','target/folio-challenge-frozen-v1','target/folio-challenge-v14',False),('development','target/folio-challenge-frozen-v2','target/folio-prospective-v14',False),('clock','target/folio-time-invariance-frozen-v1','target/folio-time-invariance-v14',False),('public','target/folio-public-rag-frozen-v1','target/folio-public-rag-v14-capacity',True)]
    if args.previous_qualification_fixtures:
        if not args.previous_qualification_base_report:raise ValueError('A prior identical base report is required for the previous qualification comparison')
        evaluations.insert(1,('previous-qualification',args.previous_qualification_fixtures,args.previous_qualification_base_report,False))
    for label,fixtures,baseline,exclude in evaluations:
        command=['training/colab/evaluate_challenge.py','--adapter',args.output+'/adapter','--base-path',base,'--training-data',args.data,'--output',f'target/{suffix}-{label}','--fixtures',fixtures]
        if baseline:command+=['--base-report-from',baseline]
        if exclude:command+=['--exclude-oversized']
        jobs.append((label,command))
    completed=[]
    for phase,arguments in jobs:
        log=Path(f'target/{suffix}-{phase}.log')
        if log.exists():raise ValueError('Do not overwrite previous logs')
        with log.open('wb') as stream:
            child=subprocess.Popen([sys.executable,'-u',*arguments],stdout=stream,stderr=subprocess.STDOUT)
            write_json(state,{'phase':phase,'status':'running','cycle_pid':os.getpid(),'child_pid':child.pid,'log':str(log),'completed':completed,'deployed':False})
            print(json.dumps({'phase':phase,'child_pid':child.pid,'log':str(log)}),flush=True)
            code=child.wait()
        if code:
            write_json(state,{'phase':phase,'status':'failed','exit_code':code,'cycle_pid':os.getpid(),'completed':completed,'log':str(log),'deployed':False})
            raise RuntimeError(f'{phase} failed with exit {code}; inspect {log}')
        completed.append(phase)
    write_json(state,{'status':'completed','completed':completed,'release_qualified':False,'deployed':False,'remaining':'Review actual answers, qualification gates, GGUF export equivalence and app integration'})
    print('Local training/evaluation cycle completed. Review release gates before export.',flush=True)


if __name__=='__main__':main()
