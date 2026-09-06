#!/usr/bin/env python3
"""Export the three NAR sub-graphs to static-shape ONNX (FP16 weights, FP32 I/O).

Buckets: encoder/projector T in {200,400,800,1200,1600,2000}; LLM S in
{64,128,256,512,768,1024,1408}. Deterministic names:
  granite-4.1-nar-encoder-t0200-fp16.onnx
  granite-4.1-nar-projector-t0200-fp16.onnx
  granite-4.1-nar-llm-s0064-fp16.onnx

Contract (docs/plano-acao... §9):
  encoder:   input_features [1,T,160] f32 -> encoder_bpe_logits [1,ceil(T/4),100352] f32,
             multilayer_features [1,T,4096] f32 (cat layers 4,8,12,-1); no mask input
             (static T => internal ones-mask, pooling lengths baked at trace).
  projector: multilayer_features [1,T,4096] f32 -> audio_embeds [1,ceil(T/15)*3,2048] f32
  llm:       inputs_embeds [1,S,2048] f32, position_ids [1,S] i64,
             attention_mask [1,S] i64 -> logits [1,S,100352] f32 (is_causal=False)

Weight strategy (matches the shipped 5.0 package): model loaded in float16;
wrappers cast f32 inputs to f16 on entry and f16 outputs back to f32, so the
traced graph keeps FP16 initializers with FP32 I/O — no post-hoc initializer
downcast (which would break ONNX type consistency).

Each export runs one bucket at a time; --bucket re-runs a single bucket;
existing outputs (size > 1 MB) are skipped (idempotent by file).
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from common import base_parser, print_step, record_step, sha256_file, step_status, work_dirs  # noqa: E402

AUDIO_T = [200, 400, 800, 1200, 1600, 2000]
LLM_S = [64, 128, 256, 512, 768, 1024, 1408]
OPSET = 17

ENCODER_SRC = r'''
import torch, torch.nn as nn
class EncoderWrapper(nn.Module):
    """f32 [1,T,160] -> (f32 bpe logits [1,ceil(T/4),V], f32 multilayer [1,T,4096])"""
    def __init__(self, model):
        super().__init__()
        self.encoder = model.encoder
        # top-level config (congelado no plano): camadas 4,8,12,-1
        self.layer_indices = model.config.encoder_layer_indices
    def forward(self, input_features):
        enc = self.encoder(input_features=input_features.to(self.encoder.dtype),
                           output_hidden_states=True)
        bpe = enc.logits.float()
        hidden = enc.all_hidden_states
        multilayer = torch.cat([hidden[i] for i in self.layer_indices], dim=-1).float()
        return bpe, multilayer
'''

PROJECTOR_SRC = r'''
import torch, torch.nn as nn
class ProjectorWrapper(nn.Module):
    """f32 [1,T,4096] -> f32 audio_embeds [1,ceil(T/15)*3,2048]"""
    def __init__(self, model):
        super().__init__()
        self.projector = model.projector
        self.dtype = model.projector.out_linear.weight.dtype
    def forward(self, multilayer_features):
        out = self.projector(multilayer_features.to(self.dtype))
        return out.float()
'''

LLM_SRC = r'''
import torch, torch.nn as nn
class LlmWrapper(nn.Module):
    """f32 embeds [1,S,2048], i64 pos [1,S], i64 mask [1,S] -> f32 logits [1,S,V]

    Computes the 4D bidirectional SDPA mask statically (attention_mask==1 over
    the real prefix, 0 on end padding) and calls the decoder layers directly —
    the transformers 5.x masking utils add dynamic shapes / control flow that
    break the legacy TorchScript exporter.
    """
    def __init__(self, model):
        super().__init__()
        self.model = model.language_model.model
        self.lm_head = model.language_model.lm_head
        self.dtype = model.language_model.lm_head.weight.dtype
        self.logits_scaling = model.language_model.config.logits_scaling
        self.rotary_emb = self.model.rotary_emb

    def forward(self, inputs_embeds, position_ids, attention_mask):
        h = inputs_embeds.to(self.dtype) * self.model.embedding_multiplier
        position_embeddings = self.rotary_emb(h, position_ids=position_ids)
        dtype = h.dtype
        min_dtype = torch.finfo(dtype).min
        # bidirectional 4D mask: [1, 1, S, S]; padded positions (mask==0) -inf
        m = attention_mask[:, None, None, :].to(dtype)
        causal = (1.0 - m) * min_dtype
        hidden = h
        for layer in self.model.layers:
            hidden = layer(hidden,
                           attention_mask=causal,
                           position_ids=position_ids,
                           position_embeddings=position_embeddings)
        hidden = self.model.norm(hidden)
        logits = self.lm_head(hidden) / self.logits_scaling
        return logits.float()
'''

WRAPPERS = {
    "enc": ("EncoderWrapper", ENCODER_SRC,
            "granite-4.1-nar-encoder-t{size:04d}-fp16.onnx"),
    "proj": ("ProjectorWrapper", PROJECTOR_SRC,
             "granite-4.1-nar-projector-t{size:04d}-fp16.onnx"),
    "llm": ("LlmWrapper", LLM_SRC,
            "granite-4.1-nar-llm-s{size:04d}-fp16.onnx"),
}


def export_one(model_source: Path, work: Path, kind: str, size: int) -> int:
    import torch
    from transformers import AutoModel

    dirs = work_dirs(work)
    exports = dirs["exports"]
    cls_name, src, name_tpl = WRAPPERS[kind]
    name = name_tpl.format(size=size)
    out_path = exports / name
    if out_path.exists() and out_path.stat().st_size > 1_000_000:
        print_step(f"{name} exists ({out_path.stat().st_size} B); skipping")
        return 0

    print_step(f"loading model fp16 for {kind} {size} ...")
    model = AutoModel.from_pretrained(
        str(model_source), trust_remote_code=True,
        attn_implementation="sdpa", dtype=torch.float16).eval()
    for p in model.parameters():
        p.requires_grad_(False)

    ns: dict = {}
    exec(src, ns)  # noqa: S102 - fixed wrapper source above
    wrapper = ns[cls_name](model)
    wrapper.eval()

    if kind == "enc":
        dummy = (torch.randn(1, size, 160, dtype=torch.float32),)
        in_names = ["input_features"]
        out_names = ["encoder_bpe_logits", "multilayer_features"]
    elif kind == "proj":
        dummy = (torch.randn(1, size, 4096, dtype=torch.float32),)
        in_names = ["multilayer_features"]
        out_names = ["audio_embeds"]
    else:
        dummy = (torch.randn(1, size, 2048, dtype=torch.float32),
                 torch.arange(size, dtype=torch.int64).unsqueeze(0),
                 torch.ones(1, size, dtype=torch.int64))
        in_names = ["inputs_embeds", "position_ids", "attention_mask"]
        out_names = ["logits"]

    # warm the wrapper once so any lazy init happens before tracing
    with torch.no_grad():
        wrapper(*[d.clone() for d in dummy])

    print_step(f"torch.onnx.export {name} (opset {OPSET}, static shapes) ...")
    torch.onnx.export(
        wrapper, dummy, str(out_path),
        input_names=in_names, output_names=out_names,
        opset_version=OPSET, do_constant_folding=True, dynamo=False,
        external_data=True,
    )
    print_step(f"exported {name} ({out_path.stat().st_size} B)")

    del model, wrapper
    import gc
    gc.collect()
    return 0


def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--stage", choices=["pilot", "batch"], default="pilot")
    ap.add_argument("--bucket", help="single bucket spec, e.g. enc:200 or llm:1024")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()
    dirs = work_dirs(work)
    source = dirs["source"]

    jobs: list[tuple[str, int]] = []
    if args.bucket:
        kind, size = args.bucket.split(":")
        jobs = [(kind, int(size))]
    elif args.stage == "pilot":
        jobs = [("enc", 200), ("proj", 200), ("llm", 64)]
    else:
        jobs = ([("enc", t) for t in AUDIO_T] + [("proj", t) for t in AUDIO_T]
                + [("llm", s) for s in LLM_S])

    failures = 0
    for kind, size in jobs:
        step = f"export_{kind}_{size}"
        if args.resume and step_status(work, step) == "passed":
            print_step(f"{step} already passed; skipping")
            continue
        record_step(work, step, "running", command=f"{__file__} --bucket {kind}:{size}")
        try:
            rc = export_one(source, work, kind, size)
        except Exception as e:  # noqa: BLE001
            record_step(work, step, "failed", exit_code=1, error=repr(e))
            print_step(f"{step} FAILED: {e!r}")
            failures += 1
            continue
        if rc != 0:
            record_step(work, step, "failed", exit_code=rc)
            failures += 1
            continue
        _, _, name_tpl = WRAPPERS[kind]
        p = dirs["exports"] / name_tpl.format(size=size)
        record_step(work, step, "passed",
                    artifacts={"onnx": str(p), "bytes": p.stat().st_size,
                               "sha256": sha256_file(p)})
        print_step(f"{step} passed")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
