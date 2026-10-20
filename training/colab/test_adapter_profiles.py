"""Real PEFT parity/merge checks for the broader training profile (CPU only)."""
import copy
import unittest
import torch
from transformers import Qwen3Config, Qwen3ForCausalLM
from peft import LoraConfig, get_peft_model, get_peft_model_state_dict
from adapter_profiles import (PROFILES, identify_profile, inherit_attention_weights,
                              adapter_update_pairs, apply_adapter_update)


class AdapterProfileTests(unittest.TestCase):
    def base(self, half=False):
        torch.manual_seed(411)
        config=Qwen3Config(vocab_size=32,hidden_size=32,intermediate_size=64,
                          num_hidden_layers=2,num_attention_heads=2,
                          num_key_value_heads=1,head_dim=16,
                          bos_token_id=1,eos_token_id=2,pad_token_id=0)
        config._attn_implementation='eager'
        model=Qwen3ForCausalLM(config)
        return model.half() if half else model

    def wrapped(self, base, profile):
        return get_peft_model(base,LoraConfig(r=8,lora_alpha=16,lora_dropout=0,
                    target_modules=list(PROFILES[profile]),bias='none',task_type='CAUSAL_LM'))

    def nonzero_outputs(self, model):
        with torch.no_grad():
            for name, parameter in model.named_parameters():
                if '.lora_B.' in name:parameter.normal_(0,0.03)

    def test_exact_profiles_and_unsupported_variants(self):
        for profile,modules in PROFILES.items():
            config={'peft_type':'LORA','r':8,'lora_alpha':16,'bias':'none','target_modules':list(modules)}
            self.assertEqual(identify_profile(config),profile)
            for field,value in [('r',16),('lora_alpha',8),('use_dora',True),
                                ('init_lora_weights','pissa'),('target_parameters',['weight']),
                                ('target_modules',['lm_head'])]:
                with self.assertRaises(ValueError):identify_profile({**config,field:value})

    def test_inheritance_preserves_real_forward_and_enables_new_gradients(self):
        base=self.base();parent=self.wrapped(copy.deepcopy(base),'attention')
        self.nonzero_outputs(parent);parent.eval()
        child=self.wrapped(copy.deepcopy(base),'full-linear')
        evidence=inherit_attention_weights(child,get_peft_model_state_dict(parent));child.eval()
        self.assertTrue(evidence['parent_factors_exact'])
        self.assertEqual(evidence['new_zero_output_factors'],10)
        ids=torch.tensor([[3,4,5,6]])
        with torch.no_grad():
            self.assertTrue(torch.equal(parent(ids).logits,child(ids).logits))
        child.train();child(input_ids=ids,labels=ids).loss.backward()
        gradients=[p.grad for name,p in child.named_parameters() if '.gate_proj.lora_B.' in name]
        self.assertTrue(gradients)
        self.assertTrue(all(gradient is not None and torch.count_nonzero(gradient).item() for gradient in gradients))

    def test_inheritance_rejects_incomplete_parent(self):
        base=self.base();parent=self.wrapped(copy.deepcopy(base),'attention')
        child=self.wrapped(copy.deepcopy(base),'full-linear')
        weights=get_peft_model_state_dict(parent);weights.pop(next(iter(weights)))
        with self.assertRaisesRegex(ValueError,'every and only'):
            inherit_attention_weights(child,weights)

    def test_streamed_merge_exactly_matches_real_peft_half_merge(self):
        base=self.base(half=True);model=self.wrapped(copy.deepcopy(base),'full-linear')
        self.nonzero_outputs(model)
        weights=get_peft_model_state_dict(model);config=model.peft_config['default'].to_dict()
        merged={key:value.clone() for key,value in base.state_dict().items()}
        pairs=adapter_update_pairs(weights,merged,config)
        self.assertEqual(len(pairs),14)
        for key,factors in pairs.items():apply_adapter_update(merged[key],factors,2)
        reference=model.merge_and_unload().state_dict()
        self.assertEqual(set(merged),set(reference))
        for key in merged:self.assertTrue(torch.equal(merged[key],reference[key]),key)

    def test_missing_merge_factor_and_wrong_base_shape_are_rejected(self):
        base=self.base();model=self.wrapped(base,'attention')
        weights=get_peft_model_state_dict(model);config=model.peft_config['default'].to_dict()
        base_keys=[key[len('base_model.model.'):].replace('.lora_A.weight','.weight')
                   for key in weights if key.endswith('.lora_A.weight')]
        incomplete=dict(weights);incomplete.pop(next(key for key in weights if key.endswith('.lora_B.weight')))
        with self.assertRaises(ValueError):adapter_update_pairs(incomplete,base_keys,config)
        factors=next(iter(adapter_update_pairs(weights,base_keys,config).values()))
        with self.assertRaisesRegex(ValueError,'shape mismatch'):
            apply_adapter_update(torch.zeros(1,1),factors,2)


if __name__=='__main__':unittest.main()
