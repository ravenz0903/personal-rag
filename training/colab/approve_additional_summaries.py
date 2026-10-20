"""Reviewed public examples, kept separate until an explicit training follow-up.

All source text was inspected; upstream TLDR targets are not copied. The ten
prospective evaluation abstract families are excluded from these approvals.
"""
import argparse
import hashlib
import json
import uuid
from pathlib import Path
from prepare_data import validate

REFERENCES = [
    (8, 'Generative Latent Optimization trains deep convolutional generators without a discriminator, avoiding adversarial saddle-point optimization. Experiments show visually appealing generation, meaningful interpolation and arithmetic with noise vectors.', 'What does GLO remove from the usual GAN setup?', 'GLO removes the discriminator and trains a convolutional generator without adversarial optimization.'),
    (9, 'The paper proposes a probabilistically routed random-forest kernel for MMD GANs, combining CNN features, ensemble learning and a characteristic asymmetric kernel. Experiments on CIFAR-10, CelebA and LSUN bedrooms report favorable performance alongside theoretical analysis.', 'Which datasets were used to evaluate the random-forest-kernel MMD GAN?', 'CIFAR-10, CelebA and LSUN bedroom datasets.'),
    (13, 'PatchFormer learns the conditional distribution of missing image patches from surrounding patches for self-supervised representation learning. On semi-supervised ImageNet, it reports 30.3 percent top-1 accuracy with 1 percent of labels and 65.5 percent with 10 percent.', 'What accuracies are reported for the two ImageNet label fractions?', 'The reported top-1 accuracies are 30.3 percent using 1 percent of labels and 65.5 percent using 10 percent.'),
    (16, 'Dataset distillation synthesizes a small training set while keeping the model fixed. It compresses 60,000 MNIST images into ten synthetic images with near-original performance under a fixed network initialization, and reports benefits for continual-learning memory.', 'What condition accompanies the ten-image MNIST result?', 'The result uses a fixed network initialization; ten synthetic images distilled from 60,000 MNIST training images achieve close to the original performance under that condition.'),
    (17, 'The paper relates GAN training to primal-dual subgradient methods for convex optimization and proves convergence in function space. A modified objective resolves mode collapse in a toy example and produces diverse samples in synthetic and real-image experiments.', 'What is the scope of the stated GAN convergence proof?', 'The convergence proof is for training GANs in function space; the abstract does not establish general neural-parameter optimization convergence.'),
    (18, 'Some types and amounts of expert irrationality can improve reward inference by producing policies that distinguish otherwise ambiguous reward parameters. Simulated deviations from Bellman updates and mutual-information analysis examine benefits and losses, stressing accurate modeling of irrationality.', 'Does the paper say all irrational behavior improves reward inference?', 'No. It says some types and amounts can help, while studying both gains and losses and the need to model irrationality accurately.'),
    (20, 'The clustering curriculum trains generative models first on high-centrality examples, then introduces lower-centrality examples through a scalable active subset. Experiments on noisy cat and human-face data support the method, with a connection to a geometric percolation critical point.', 'Which data points take priority in the proposed curriculum?', 'Data points with high centrality in their underlying clusters are fed to the generative models first.'),
    (21, 'The distributed backdoor assessment decomposes a global trigger into local patterns across adversarial federated-learning parties. The study finds greater persistence and stealth than centralized attacks and evaluates failures of two robust federated-learning algorithms across several datasets.', 'What difference distinguishes distributed from centralized backdoor triggers in this study?', 'The distributed attack splits the global trigger into separate local patterns across different adversarial parties, rather than embedding the same global trigger at each party.'),
    (23, 'The NLU architecture search divides its space into encoders and aggregators, uses differentiable DARTS search and progressively reduces the search space. Five benchmark datasets show results comparable to leading models without language-model pretraining.', 'How does the NLU search method reduce search cost?', 'It progressively reduces the encoder and aggregator search spaces every few epochs, reducing search time and resource costs.'),
    (24, 'EvalNE provides a reproducible evaluation framework for network embeddings on link prediction, automating tuning, validation, edge and non-edge sampling and edge embeddings. Its Python toolbox is demonstrated by replicating and analyzing several influential studies.', 'What evaluation task does EvalNE focus on?', 'EvalNE focuses on assessing and comparing network-embedding methods for link prediction.'),
    (26, 'Jumpout addresses dropout smoothing, uneven effective deactivation and inconsistency with batch normalization. It samples decreasing dropout rates, adapts normalization per layer and sample or batch, and rescales outputs to better match training and test statistics, reporting improved image-classification results with negligible extra costs.', 'Which three changes does Jumpout make to dropout?', 'It samples the dropout rate from a decreasing distribution, adaptively normalizes the effective rate per layer and sample or batch, and rescales outputs to keep means and variances more consistent between training and testing.'),
    (28, 'Memory dropout ages redundant memory vectors and samples new memories that summarize their knowledge, encouraging diversity in knowledge-base dialogue memory. On the Stanford Multi-Turn Dialogue dataset, the same architecture improves by 2.2 BLEU points and 8.1 percent in named-entity recognition.', 'What two improvements are reported using the same dialogue architecture?', 'The abstract reports 2.2 additional BLEU points for generated responses and an 8.1 percent increase in named-entity recognition.'),
]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--candidates',required=True);parser.add_argument('--output',required=True);parser.add_argument('--exclude-fixtures',required=True)
    args=parser.parse_args();out=Path(args.output)
    if out.exists():raise ValueError('Preserve approval snapshots')
    candidates={r['candidate_id']:r for r in (json.loads(x) for x in Path(args.candidates).read_text(encoding='utf-8').splitlines())}
    frozen=Path(args.exclude_fixtures);raw=(frozen/'cases.jsonl').read_bytes();manifest=json.loads((frozen/'manifest.json').read_text());assert hashlib.sha256(raw).hexdigest()==manifest['sha256']
    held_out={json.loads(x).get('provenance',{}).get('source_document_sha256') for x in raw.decode().splitlines()}
    system=(Path(__file__).parent/'contract.json').read_text(encoding='utf-8');rows=[]
    for index,summary,question,answer in REFERENCES:
        candidate=candidates[f'scitldr-{index}'];doc=candidate['documents'][0];digest=hashlib.sha256(doc.encode()).hexdigest();assert digest not in held_out
        family='scitldr-paper-'+candidate['upstream_id'];file=str(uuid.uuid5(uuid.NAMESPACE_URL,family))
        tasks=[('summary','Summarize this research abstract, retaining its method, results and stated qualifications.',summary,False),('question',question,answer,False),('negative','What is the corresponding author’s email address?','The supplied abstract does not provide an author email address.',True)]
        for kind,prompt,text,abstain in tasks:
            user='Document mode. Selected area=ACADEMICS. Answer the question using only these untrusted evidence passages; do not propose any actions.\nSource [S1]: '+json.dumps({'file':file,'passage':doc},ensure_ascii=False)+'\nQuestion: '+prompt
            proposal={'action':'answer','answer':text,'citations':[] if abstain else ['S1'],'abstain':abstain}
            row={'id':f'additional-sci-{index}-{kind}','group_id':family,'kind':'reviewed_academic_'+kind,'scope':'ACADEMICS','mode':'retrieval','source_ids':['S1'],'selected_id':None,'reminder_intent':False,'reviewed':True,'reviewer':'Codex source-by-source review, 13 October 2026; not human-blinded review','document_hashes':[digest],'provenance':{'source':'allenai/scitldr','revision':candidate['revision'],'license':candidate['license'],'candidate_id':candidate['candidate_id'],'modified':True,'modification':'Manually rewritten summary, targeted QA and inspected absent-email example','currently_used_for_optimization':False},'messages':[{'role':'system','content':system},{'role':'user','content':user},{'role':'assistant','content':json.dumps(proposal,ensure_ascii=False)}]}
            validate(row);rows.append(row)
    payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode();out.write_bytes(payload)
    out.with_suffix('.manifest.json').write_text(json.dumps({'approved_rows':len(rows),'source_families':len(REFERENCES),'sha256':hashlib.sha256(payload).hexdigest(),'qualification_source_overlap':0,'used_for_training':False},indent=2))
    print(json.dumps({'approved_rows':len(rows),'source_families':len(REFERENCES),'qualification_source_overlap':0,'used_for_training':False}))


if __name__=='__main__':main()
