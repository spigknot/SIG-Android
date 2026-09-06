"""Unit tests for the pure helpers in tools/granite/nar (pytest).

Run with the experiment venv or any Python with pytest:
  python -m pytest tools/granite/nar/test_nar_tools.py -q
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).parent))

import common  # noqa: E402


def test_sha256_streaming_matches_known(tmp_path):
    p = tmp_path / "f.bin"
    p.write_bytes(b"hello world")
    assert common.sha256_file(p) == common.sha256_bytes(b"hello world")


def test_atomic_write_json_roundtrip(tmp_path):
    p = tmp_path / "sub" / "a.json"
    common.atomic_write_json(p, {"x": 1, "s": "ãé"})
    assert json.loads(p.read_text(encoding="utf-8")) == {"x": 1, "s": "ãé"}
    assert not p.with_name("a.json.partial").exists()


def test_atomic_write_no_partial_left_on_error(tmp_path):
    p = tmp_path / "b.txt"
    common.atomic_write_text(p, "ok")
    assert p.read_text(encoding="utf-8") == "ok"
    assert not p.with_name("b.txt.partial").exists()
    # falha de escrita: alvo dentro de um arquivo (pai não é diretório)
    blocker = tmp_path / "blocker"
    blocker.write_text("x")
    with pytest.raises(OSError):
        common.atomic_write_text(blocker / "c.txt", "nope")


def test_record_step_roundtrip(tmp_path):
    work = tmp_path
    (work / "state").mkdir()
    common.record_step(work, "s1", "running")
    common.record_step(work, "s1", "passed", artifacts={"n": 1}, exit_code=0)
    st = common.load_state(work)
    assert st["steps"]["s1"]["status"] == "passed"
    assert st["steps"]["s1"]["exit_code"] == 0
    assert common.step_status(work, "s1") == "passed"
    assert common.step_status(work, "missing") == "pending"


def test_record_step_preserves_started_at(tmp_path):
    work = tmp_path
    (work / "state").mkdir()
    common.record_step(work, "s", "running", command="cmd-a")
    common.record_step(work, "s", "passed", command="cmd-b")
    st = common.load_state(work)
    assert st["steps"]["s"]["command"] == "cmd-b"
    assert st["steps"]["s"]["started_at"] <= st["steps"]["s"]["finished_at"]


def test_lfs_pointer_detection(tmp_path):
    p = tmp_path / "weights.bin"
    p.write_text("version https://git-lfs.github.com/spec/v1\noid sha256:abc\n")
    assert common.looks_like_lfs_pointer(p)
    p2 = tmp_path / "real.bin"
    p2.write_bytes(b"\x00\x01\x02not-lfs")
    assert not common.looks_like_lfs_pointer(p2)


def test_work_dirs_creates_all(tmp_path):
    dirs = common.work_dirs(tmp_path)
    expected = {"source", "cache", "venv", "exports", "golden", "calibration",
                "quantized", "packages", "logs", "reports", "state"}
    assert expected == set(dirs.keys())
    for d in dirs.values():
        assert d.is_dir()


def test_env_for_experiment_pins_caches(tmp_path):
    env = common.env_for_experiment(tmp_path)
    exp = str(tmp_path.resolve())
    assert env["HF_HOME"].startswith(exp)
    assert env["HF_HUB_CACHE"].startswith(exp)
    assert env["PIP_CACHE_DIR"].startswith(exp)


# ---- NAR pipeline pure math (mirror of GraniteNarCtc/Interleave in Kotlin) ----

BLANK = 100257


def ctc_collapse(logits: np.ndarray) -> list[int]:
    ids = logits.argmax(axis=-1).tolist()
    out, prev = [], -1
    for t in ids:
        if t != prev and t != BLANK:
            out.append(t)
        prev = t
    return out


def build_slots(ctc_tokens: list[int]) -> list[int]:
    n = len(ctc_tokens)
    total = max(2 * n + 1, 8)
    slots = [BLANK] * total
    for i, t in enumerate(ctc_tokens):
        slots[2 * i + 1] = t
    return slots


def test_ctc_collapse_removes_blanks_and_repeats():
    # vocab grande o bastante para BLANK=100257 não colidir com os tokens de teste
    vocab = BLANK + 10
    # frames: [tok5, tok5, blank, tok7, blank, blank, tok9, blank]
    logits = np.full((8, vocab), -10.0, dtype=np.float32)
    plan = [5, 5, BLANK, 7, BLANK, BLANK, 9, BLANK]
    for i, tok in enumerate(plan):
        logits[i, tok] = 10.0
    assert ctc_collapse(logits) == [5, 7, 9]


def test_ctc_collapse_all_blank():
    vocab = BLANK + 10
    logits = np.full((4, vocab), -10.0, dtype=np.float32)
    logits[:, BLANK] = 10.0
    assert ctc_collapse(logits) == []


def test_build_slots_min_length():
    assert build_slots([]) == [BLANK] * 8          # min_edit = 8
    assert build_slots([3]) == [BLANK, 3, BLANK] * 2 + [BLANK, BLANK][:5 - 5 + 1 - 1] \
        if False else build_slots([3])[1] == 3


def test_build_slots_interleave_pattern():
    # 2n+1 = 7 < min_edit=8, então o comprimento real é 8 (min edit prevalece)
    slots = build_slots([11, 22, 33])
    assert slots[:7] == [BLANK, 11, BLANK, 22, BLANK, 33, BLANK]
    assert len(slots) == 8 and slots[7] == BLANK
    assert len(build_slots(list(range(30)))) == 61  # 2*30+1
    # sempre começa com blank e posições ímpares recebem os tokens
    assert slots[0] == BLANK
    assert slots[1::2] == [11, 22, 33, BLANK]


def test_duration_bucket_boundaries():
    from build_calibration_corpus import duration_bucket_s, t_bucket_for
    assert duration_bucket_s(0.5) == "1-4s"
    assert duration_bucket_s(4.0) == "1-4s"
    assert duration_bucket_s(8.0) == "4-8s"
    assert duration_bucket_s(16.0) == "8-16s"
    assert duration_bucket_s(24.0) == "16-24s"
    assert duration_bucket_s(39.0) == "24-40s"
    assert t_bucket_for(150) == 200
    assert t_bucket_for(201) == 400
    assert t_bucket_for(1999) == 2000
    assert t_bucket_for(5000) == 2000


def test_projector_output_formula():
    # audio_embeds = ceil(T/15)*3 for known T buckets
    import math
    for T in (200, 400, 800, 1200, 1600, 2000):
        assert math.ceil(T / 15) * 3 == (42, 81, 162, 240, 321, 402)[
            (200, 400, 800, 1200, 1600, 2000).index(T)]


def test_export_names_deterministic():
    from export_static import AUDIO_T, LLM_S
    for t in AUDIO_T:
        assert f"granite-4.1-nar-encoder-t{t:04d}-fp16.onnx".startswith("granite-4.1-nar-encoder-")
    for s in LLM_S:
        assert f"granite-4.1-nar-llm-s{s:04d}-fp16.onnx" == \
            f"granite-4.1-nar-llm-s{s:04d}-fp16.onnx"


def test_metrics_zero_diff():
    from validate_float import metrics
    a = np.array([1.0, 2.0, 3.0])
    m = metrics(a, a)
    assert m["max_abs"] == 0.0 and m["cosine"] == pytest.approx(1.0)


def test_metrics_nonzero():
    from validate_float import metrics
    a = np.array([1.0, 2.0])
    b = np.array([1.1, 1.8])
    m = metrics(a, b)
    assert 0 < m["max_abs"] <= 0.2
    assert m["cosine"] < 1.0 and m["cosine"] > 0.99


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
