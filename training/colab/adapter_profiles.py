"""Explicit, bounded Qwen3 LoRA profiles; no implicit adapter conversion."""

PROFILES = {
    'attention': ('q_proj', 'v_proj'),
    'full-linear': ('q_proj', 'k_proj', 'v_proj', 'o_proj', 'gate_proj', 'up_proj', 'down_proj'),
}


def identify_profile(config):
    if (config.get('peft_type') != 'LORA' or config.get('r') != 8
            or config.get('lora_alpha') != 16 or config.get('bias') != 'none'
            or config.get('task_type', 'CAUSAL_LM') != 'CAUSAL_LM'
            or config.get('init_lora_weights', True) is not True
            or any(config.get(key) for key in (
                'use_dora', 'use_rslora', 'modules_to_save', 'rank_pattern',
                'alpha_pattern', 'fan_in_fan_out', 'layer_replication', 'lora_bias',
                'alora_invocation_tokens', 'target_parameters', 'use_qalora',
                'trainable_token_indices', 'exclude_modules', 'layers_to_transform',
                'layers_pattern', 'megatron_config'))):
        raise ValueError('Unsupported LoRA configuration')
    targets = config.get('target_modules')
    if not isinstance(targets, (list, tuple, set)):
        raise ValueError('Adapter must name its exact target modules')
    for name, modules in PROFILES.items():
        if set(targets) == set(modules):
            return name
    raise ValueError('Unsupported LoRA target modules')


def inherit_attention_weights(model, parent_weights):
    """Copy every parent factor; extra projections retain zero output factors.

    A rank-8 q/v parent and full-linear child have the same alpha/r scaling.
    Additional zero B factors preserve the parent's initial inference function.
    No parent factor is silently skipped, reshaped, merged or approximated.
    """
    import torch
    from peft import get_peft_model_state_dict, set_peft_model_state_dict
    state = get_peft_model_state_dict(model)
    required = {key for key in state if any('.' + module + '.lora_' in key
                                           for module in PROFILES['attention'])}
    if set(parent_weights) != required:
        raise ValueError('Parent must contain every and only expected q/v factor')
    for key, value in parent_weights.items():
        if value.shape != state[key].shape or not torch.isfinite(value).all():
            raise ValueError('Invalid inherited adapter factor: ' + key)
    result = set_peft_model_state_dict(model, parent_weights)
    if result.unexpected_keys:
        raise ValueError('Unexpected inherited adapter keys')
    loaded = get_peft_model_state_dict(model)
    for key, value in parent_weights.items():
        if not torch.equal(loaded[key].detach().cpu(), value.detach().cpu()):
            raise ValueError('Inherited adapter factor changed: ' + key)
    extra_b = [key for key in loaded if key not in required and key.endswith('.lora_B.weight')]
    if not extra_b or any(torch.count_nonzero(loaded[key]).item() for key in extra_b):
        raise ValueError('Added adapter projections must start with zero output')
    return {'inherited_factors': len(required), 'new_zero_output_factors': len(extra_b),
            'parent_factors_exact': True, 'scaling_unchanged': True}


def adapter_update_pairs(weights, base_keys, config):
    """Keep low-rank factors, never precompute dense deltas for every layer."""
    profile = identify_profile(config)
    expected = {key for key in base_keys if any(key.endswith('.' + module + '.weight')
                                               for module in PROFILES[profile])}
    pairs = {}
    used = set()
    for key, a in weights.items():
        if not key.endswith('.lora_A.weight'):
            continue
        if not key.startswith('base_model.model.'):
            raise ValueError('Unexpected adapter factor prefix')
        bkey = key.replace('.lora_A.weight', '.lora_B.weight')
        basekey = key[len('base_model.model.'):].replace('.lora_A.weight', '.weight')
        if bkey not in weights or basekey not in expected:
            raise ValueError('Unexpected adapter target or missing output factor')
        b = weights[bkey]
        if a.ndim != 2 or b.ndim != 2 or a.shape[0] != config['r'] or b.shape[1] != config['r']:
            raise ValueError('Invalid adapter rank/shape')
        pairs[basekey] = (a, b)
        used.update((key, bkey))
    if set(pairs) != expected or used != set(weights):
        raise ValueError('Adapter factors must exactly cover the selected base projections')
    return pairs


def apply_adapter_update(tensor, factors, scaling):
    """One dense FP32 delta at a time; match PEFT's in-place dtype rounding."""
    import torch
    a, b = factors
    if tensor.ndim != 2 or (b.shape[0], a.shape[1]) != tensor.shape:
        raise ValueError('Adapter/base weight shape mismatch')
    if not torch.isfinite(a).all() or not torch.isfinite(b).all():
        raise ValueError('Non-finite adapter factor')
    delta = b.float() @ a.float()
    delta.mul_(scaling)
    tensor.add_(delta)
    if not torch.isfinite(tensor).all():
        raise ValueError('Non-finite merged model weight')
