#!/usr/bin/env python3
"""QNN QDQ quantization (U16 activations / U8 weights) for float static exports.

Pipeline per sub-graph/bucket:
  1. qnn_preprocess_model (fixes shapes, folds, prepares for QNN)
  2. CalibrationDataReader over real CHAINED inputs (encoder<-features,
     projector<-encoder multilayer, LLM<-projector audio embeds + slot embeds)
  3. get_qnn_qdq_config -> activations QUInt16, weights QUInt8
  4. quantize_static -> <name>.preproc.onnx / <name>-qdq-u16u8.onnx
  5. ORT CPU validation vs float (QDQ must run on CPU EP for offline parity)

Per user decision (30/08): quantize ONLY the approved pilot trio
(enc:200 + proj:200 + llm:64) after the end-to-end text gate passes; do NOT
start batch quantization until each float baseline passes its own gate.

Usage:
  python quantize_qnn.py --work-dir E:/... --target enc:200
  python quantize_qnn.py --work-dir E:/... --target proj:200
  python quantize_qnn.py --work-dir E:/... --target llm:64
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from common import (atomic_write_json, base_parser, print_step,  # noqa: E402
                    record_step, sha256_file, step_status, work_dirs)

BLANK = 100257
EMBED_MULT = 12.0
MIN_EDIT = 8
LLM_S = [64, 128, 256, 512, 768, 1024, 1408]


def qnn_available() -> bool:
    try:
        from onnxruntime.quantization import qnn_preprocess_model  # noqa: F401
        return True
    except ImportError:
        return False


def _preprocess(src: Path, pre: Path) -> None:
    try:
        from onnxruntime.quantization import qnn_preprocess_model
    except ImportError:
        qnn_preprocess_model = None
    if qnn_preprocess_model is not None:
        print_step(f"qnn_preprocess_model {src.name}")
        try:
            qnn_preprocess_model(str(src), str(pre), flag_add_trim_layout=True)
            return
        except Exception as e:  # noqa: BLE001
            print_step(f"preprocess failed ({e!r}); using raw float as preproc input")
    import shutil
    shutil.copyfile(src, pre)


def _quantize(pre: Path, out: Path, reader, extra: dict | None = None) -> None:
    from onnxruntime.quantization import (QuantFormat,  # noqa
                                          get_qnn_qdq_config, quantize_static)
    config = get_qnn_qdq_config(str(pre), str(pre))
    config.extra_options["ActivationSymmetric"] = False
    try:
        from onnx import TensorProto
        config.default_activation_type = TensorProto.UINT16
        config.default_weight_type = TensorProto.UINT8
    except Exception as e:  # noqa: BLE001
        print_step(f"quant type override failed: {e!r}")
    print_step(f"quantize_static {out.name} (QDQ U16/U8)")
    opts = {"ActivationSymmetric": False, "WeightSymmetric": True}
    if extra:
        opts.update(extra)
    quantize_static(model_input=str(pre), model_output=str(out),
                    calibration_data_reader=reader,
                    quant_format=QuantFormat.QDQ,
                    activation_type=config.default_activation_type,
                    weight_type=config.default_weight_type,
                    per_channel=config.extra_options.get("PerChannel", True),
                    extra_options=opts)


def _make_reader(feeds_iter):
    from onnxruntime.quantization import CalibrationDataReader

    class Reader(CalibrationDataReader):
        def __init__(self, it):
            self.it = iter(feeds_iter)

        def get_next(self):
            return next(self.it, None)

    return Reader(feeds_iter)


# ---------------------------------------------------------------- encoder ----

def collect_encoder_calib(work: Path, T: int, max_files: int = 8) -> list[np.ndarray]:
    """Real features from the calibration corpus, padded to T."""
    import soundfile as sf
    dirs = work_dirs(work)
    calib = dirs["calibration"]
    manifest = calib / "corpus-manifest.jsonl"
    entries = [json.loads(l) for l in manifest.read_text(encoding="utf-8").splitlines() if l.strip()]
    sys.path.insert(0, str(Path(__file__).parent))
    from capture_chained_calibration import frontend_features
    feats_list = []
    for e in entries[:max_files]:
        data, sr = sf.read(str(calib / e["file"]), dtype="float32", always_2d=True)
        wav = data.mean(axis=1)
        if sr != 16000:
            continue
        f = frontend_features(wav)
        x = np.zeros((1, T, 160), dtype=np.float32)
        take = min(T, f.shape[0])
        x[:, :take, :] = f[:take]
        feats_list.append(x)
        if len(feats_list) >= max_files:
            break
    return feats_list


def quantize_encoder(work: Path, T: int, calib_feats: list[np.ndarray]) -> dict:
    exports = work_dirs(work)["exports"]
    quant = work_dirs(work)["quantized"]
    src = exports / f"granite-4.1-nar-encoder-t{T:04d}-fp16.onnx"
    pre = quant / f"granite-4.1-nar-encoder-t{T:04d}-fp16.preproc.onnx"
    out = quant / f"granite-4.1-nar-encoder-t{T:04d}-qdq-u16u8.onnx"
    _preprocess(src, pre)
    _quantize(pre, out, _make_reader([{"input_features": f} for f in calib_feats]))
    return {"preproc": str(pre), "qdq": str(out)}


# --------------------------------------------------------------- projector ----

def collect_projector_calib(work: Path, T: int, max_files: int = 8) -> list[np.ndarray]:
    """Chained: multilayer_features produced by the REAL float encoder."""
    import onnxruntime as ort
    dirs = work_dirs(work)
    exports = dirs["exports"]
    enc_path = exports / f"granite-4.1-nar-encoder-t{T:04d}-fp16.onnx"
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    sess = ort.InferenceSession(str(enc_path), so, providers=["CPUExecutionProvider"])
    out = []
    for x in collect_encoder_calib(work, T, max_files):
        bpe, ml = sess.run(None, {"input_features": x})
        ml_np = np.asarray(ml)
        if ml_np.ndim == 3:
            ml_np = ml_np[0]
        out.append(ml_np.astype(np.float32)[None, :, :])
    return out


def quantize_projector(work: Path, T: int, calib_ml: list[np.ndarray]) -> dict:
    exports = work_dirs(work)["exports"]
    quant = work_dirs(work)["quantized"]
    src = exports / f"granite-4.1-nar-projector-t{T:04d}-fp16.onnx"
    pre = quant / f"granite-4.1-nar-projector-t{T:04d}-fp16.preproc.onnx"
    out = quant / f"granite-4.1-nar-projector-t{T:04d}-qdq-u16u8.onnx"
    _preprocess(src, pre)
    _quantize(pre, out, _make_reader([{"multilayer_features": m} for m in calib_ml]))
    return {"preproc": str(pre), "qdq": str(out)}


# ------------------------------------------------------------------- llm ----

def collect_llm_calib(work: Path, S: int, max_files: int = 8) -> list[dict[str, np.ndarray]]:
    """Chained: audio embeds (encoder+projector) + slot embeds (embed table)."""
    import torch
    import onnxruntime as ort
    from transformers import AutoModel
    dirs = work_dirs(work)
    exports = dirs["exports"]
    source = dirs["source"]

    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    enc = ort.InferenceSession(str(exports / f"granite-4.1-nar-encoder-t0200-fp16.onnx"),
                               so, providers=["CPUExecutionProvider"])
    proj = ort.InferenceSession(str(exports / f"granite-4.1-nar-projector-t0200-fp16.onnx"),
                               so, providers=["CPUExecutionProvider"])

    print_step("carregando modelo torch para embeddings de slots (fp16) ...")
    model = AutoModel.from_pretrained(str(source), trust_remote_code=True,
                                      attn_implementation="sdpa",
                                      dtype=torch.float16).eval()
    embed_w = model.language_model.model.embed_tokens.weight

    feeds = []
    for x in collect_encoder_calib(work, 200, max_files):
        bpe, ml = enc.run(None, {"input_features": x})
        ml_np = np.asarray(ml)
        if ml_np.ndim == 3:
            ml_np = ml_np[0]
        audio = np.asarray(proj.run(None, {"multilayer_features": ml_np[None, :, :]})[0])
        if audio.ndim == 3:
            audio = audio[0]
        T_real = int(np.count_nonzero(x[0, :, 0]))  # frames reais (linhas não-zero)
        # melhor estimativa: frames com energia; fallback = fill do bucket
        valid_audio = T_real // 5
        if valid_audio < 1:
            valid_audio = 1
        a = audio[:valid_audio] / EMBED_MULT
        bpe_np = np.asarray(bpe)
        if bpe_np.ndim == 3:
            bpe_np = bpe_np.reshape(-1, bpe_np.shape[-1])
        valid_bpe = (T_real + 3) // 4
        ids = bpe_np[:valid_bpe].argmax(axis=-1)
        ctc, prev = [], -1
        for t in ids.tolist():
            if t != prev and t != BLANK:
                ctc.append(t)
            prev = t
        slots = [BLANK] * max(2 * len(ctc) + 1, MIN_EDIT)
        for i, tok in enumerate(ctc):
            slots[2 * i + 1] = tok
        with torch.no_grad():
            text_emb = embed_w[torch.tensor(slots)].float().numpy()
        emb = np.concatenate([a, text_emb], axis=0)
        S_real = emb.shape[0]
        if S_real > S:
            continue
        x_emb = np.zeros((1, S, 2048), dtype=np.float32)
        x_emb[0, :S_real, :] = emb
        mask = np.zeros((1, S), dtype=np.int64)
        mask[0, :S_real] = 1
        pos = np.arange(S, dtype=np.int64)[None, :]
        feeds.append({"inputs_embeds": x_emb, "position_ids": pos, "attention_mask": mask})
        if len(feeds) >= max_files:
            break
    del model, embed_w
    import gc
    gc.collect()
    return feeds


def quantize_llm(work: Path, S: int, calib: list[dict[str, np.ndarray]]) -> dict:
    exports = work_dirs(work)["exports"]
    quant = work_dirs(work)["quantized"]
    src = exports / f"granite-4.1-nar-llm-s{S:04d}-fp16.onnx"
    pre = quant / f"granite-4.1-nar-llm-s{S:04d}-fp16.preproc.onnx"
    out = quant / f"granite-4.1-nar-llm-s{S:04d}-qdq-u16u8.onnx"
    _preprocess(src, pre)
    _quantize(pre, out, _make_reader(calib))
    return {"preproc": str(pre), "qdq": str(out)}


# ------------------------------------------------------------------ main ----

def main() -> int:
    ap = base_parser(__doc__)
    ap.add_argument("--target", required=True, help="enc:200 | proj:200 | llm:64")
    ap.add_argument("--calib-files", type=int, default=8)
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()
    work = Path(args.work_dir).resolve()

    kind, size = args.target.split(":")
    size = int(size)
    step = f"quantize_{kind}_{size}"
    if args.resume and step_status(work, step) == "passed":
        print_step(f"{step} already passed; skipping")
        return 0
    if not qnn_available():
        record_step(work, step, "failed", exit_code=3,
                    error="onnxruntime.quantization QNN tools unavailable")
        print_step("QNN quantization tooling not available in this ORT build")
        return 3

    record_step(work, step, "running", command=f"{__file__} --target {args.target}")
    dirs = work_dirs(work)

    if kind == "enc":
        calib = collect_encoder_calib(work, size, args.calib_files)
        artifacts = quantize_encoder(work, size, calib)
    elif kind == "proj":
        calib = collect_projector_calib(work, size, args.calib_files)
        artifacts = quantize_projector(work, size, calib)
    elif kind == "llm":
        calib = collect_llm_calib(work, size, args.calib_files)
        if not calib:
            record_step(work, step, "failed", exit_code=5,
                        error=f"no calibration feeds fit S={size} (S_real > bucket)")
            print_step(f"{step} failed: no calibration feeds fit bucket S={size}")
            return 5
        artifacts = quantize_llm(work, size, calib)
    else:
        record_step(work, step, "failed", exit_code=4,
                    error=f"unknown kind {kind}")
        return 4

    report = {"target": args.target, "artifacts": artifacts,
              "calib_files": len(calib), "activation": "QUInt16", "weight": "QUInt8"}
    for k, v in artifacts.items():
        report[f"{k}_sha256"] = sha256_file(Path(v))
        report[f"{k}_bytes"] = Path(v).stat().st_size
    atomic_write_json(dirs["reports"] / f"quantize-{kind}-{size}.json", report)
    record_step(work, step, "passed", artifacts=artifacts)
    print_step(f"{step} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
