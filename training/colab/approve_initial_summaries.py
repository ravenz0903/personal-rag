"""Record eight individually inspected summaries. This is not bulk approval."""
import argparse
import json
from pathlib import Path

SUMMARIES = [
    'The paper characterizes critical points of square-loss functions in linear neural networks and uses them to study loss landscapes. It finds no spurious local minima in linear networks, but non-global local minima can exist in one-hidden-layer ReLU networks.',
    'The study evaluates sign-symmetry and feedback alignment on ImageNet and MS COCO. Sign-symmetry shares weight signs rather than magnitudes between feedback and feedforward paths, and its classification performance approaches backpropagation.',
    'The 2-simplicial Transformer extends attention using higher-dimensional attention and tensor products of value vectors. The paper demonstrates its usefulness as an inductive bias for logical reasoning in deep reinforcement learning.',
    'Tensor-Train RNNs learn nonlinear dynamics with higher-order moments and state transitions. Tensor-train decomposition reduces parameters, and experiments report better long-term forecasting than general RNNs and LSTMs on simulated, climate, and traffic data.',
    'The paper proposes variational message passing for models combining deep models and probabilistic graphical models. Structured inference networks support amortized and natural-gradient inference.',
    'The method models a weight matrix with a mixture of low-rank factorizations whose coefficients depend on the input. Language-modeling and image-classification experiments show better computation efficiency while maintaining or sometimes improving full-rank accuracy.',
    'Progressive Compressed Records split examples into progressively higher-fidelity representations without increasing total data size. Experiments retain high accuracy under aggressive compression and report an average twofold speedup over JPEG-based baseline formats.',
    'Gradient rescaling changes the magnitude of logit gradients to emphasize easier examples as label noise increases. The paper links this to regularization and reports improved robustness on noisy data, including CIFAR100 with 40 percent noisy labels.',
]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidates',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args();root=Path(__file__).resolve().parent
    candidates=[json.loads(line) for line in Path(args.candidates).read_text(encoding='utf-8').splitlines()]
    system=(root/'contract.json').read_text(encoding='utf-8');approved=[]
    for i,answer in enumerate(SUMMARIES):
        candidate=candidates[i];assert candidate['candidate_id']==f'scitldr-{i}'
        sid=f'SCITLDR-{candidate["upstream_id"]}'
        proposal={'action':'answer','answer':answer,'citations':[sid],'abstain':False}
        approved.append({'id':'reviewed-sci-'+str(i),'group_id':'scitldr-paper-'+candidate['upstream_id'],'kind':'reviewed_academic_summary','split':'train','scope':'ACADEMICS','mode':'retrieval','source_ids':[sid],'selected_id':None,'reminder_intent':False,'reviewed':True,'reviewer':'Codex source-by-source manual review, 12 October 2026; not human-blinded review','document_hashes':candidate['document_hashes'],'provenance':{'source':'allenai/scitldr','revision':candidate['revision'],'license':candidate['license'],'original_candidate_id':candidate['candidate_id'],'modified':True,'modification':'Rewrote summary against abstract; added contract-v2 citation and abstention fields'},'messages':[{'role':'system','content':system},{'role':'user','content':f'Trusted context: area=ACADEMICS; document mode. Summarize this research abstract using only the source. Source [{sid}]: '+candidate['documents'][0]},{'role':'assistant','content':json.dumps(proposal,ensure_ascii=False)}]})
    path=Path(args.output)
    if path.exists():raise ValueError('Existing approval snapshot is preserved')
    path.write_bytes(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in approved).encode())
    print('Wrote eight reviewed training summaries; all other candidates remain unapproved.')

if __name__=='__main__':main()
