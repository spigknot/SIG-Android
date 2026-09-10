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


def test_external_data_files_covers_per_tensor_layout(tmp_path):
    """Vacina: o export TorchScript grava UM ARQUIVO POR TENSOR (location = nome do
    tensor), nao `<onnx>.data`. O empacotador so olhava o layout legado e publicou
    7 shells LLM de 802 KB sem os ~3,3 GB de pesos no R2 (nar-qnn-20260829-223957)."""
    import onnx
    from onnx import helper, numpy_helper
    from build_experiment_manifest import external_data_files

    exports = tmp_path / "exports"
    exports.mkdir()
    shell = exports / "m.onnx"

    # modelo com external data por-tensor (location = nome do tensor, sem offsets)
    w1 = numpy_helper.from_array(np.zeros((4, 4), dtype=np.float32), name="w_one")
    w2 = numpy_helper.from_array(np.ones((2, 2), dtype=np.float32), name="w_two")
    graph = helper.make_graph(
        [helper.make_node("Identity", ["w_one"], ["out"])], "g", [],
        [helper.make_tensor_value_info("out", onnx.TensorProto.FLOAT, [4, 4])],
        initializer=[w1, w2])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save_model(model, str(shell), save_as_external_data=True,
                    all_tensors_to_one_file=False, size_threshold=0)
    for name in ("w_one", "w_two"):
        (exports / name).write_bytes(b"\x00" * 16)  # dados externos por tensor

    found = {p.name for p in external_data_files(shell)}
    assert {"w_one", "w_two"} <= found, found


def test_external_data_files_covers_legacy_single_file(tmp_path):
    from build_experiment_manifest import external_data_files

    exports = tmp_path / "exports"
    exports.mkdir()
    shell = exports / "m.onnx"
    shell.write_bytes(b"not-a-real-onnx")  # shell ilegivel: so o legado vale
    (exports / "m.onnx.data").write_bytes(b"\x00" * 8)
    assert [p.name for p in external_data_files(shell)] == ["m.onnx.data"]


def test_external_data_files_reports_missing(tmp_path):
    import onnx
    from onnx import helper, numpy_helper
    from build_experiment_manifest import external_data_files

    exports = tmp_path / "exports"
    exports.mkdir()
    shell = exports / "m.onnx"
    w = numpy_helper.from_array(np.zeros((2, 2), dtype=np.float32), name="w_missing")
    graph = helper.make_graph([helper.make_node("Identity", ["w_missing"], ["o"])], "g", [],
                              [helper.make_tensor_value_info("o", onnx.TensorProto.FLOAT, [2, 2])],
                              initializer=[w])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    onnx.save_model(model, str(shell), save_as_external_data=True,
                    all_tensors_to_one_file=False, size_threshold=0)
    (exports / "w_missing").unlink(missing_ok=True)  # dado externo perdido
    assert external_data_files(shell) == []


def test_dedup_entries_keeps_first_and_reports_dups():
    """Vacina: FLEURS repete `id` entre takes -> nomes de arquivo colidem e o
    manifest fica com mais linhas do que arquivos (82 linhas / 79 arquivos).
    Dedup por caminho, mantendo a PRIMEIRA, e reportando o que caiu."""
    from build_calibration_corpus import dedup_entries

    entries = [
        {"file": "pt_br_1637.wav", "id": "1637"},
        {"file": "pt_br_1637.wav", "id": "1637"},
        {"file": "de_de_1520.wav", "id": "1520"},
    ]
    unique, dups = dedup_entries(entries)
    assert [e["file"] for e in unique] == ["pt_br_1637.wav", "de_de_1520.wav"]
    assert dups == ["pt_br_1637.wav"]


def test_dedup_entries_noop_when_unique():
    from build_calibration_corpus import dedup_entries

    entries = [{"file": f"x_{i}.wav"} for i in range(5)]
    unique, dups = dedup_entries(entries)
    assert len(unique) == 5 and dups == []


def test_lang_seed_is_stable_across_processes():
    """Vacina: `hash(lang)` do Python e randomizado por processo (PYTHONHASHSEED),
    entao o corpus "deterministico por seed" trocava de amostras a cada run
    (duas execucoes com a mesma semente: apenas 27 de 82 amostras em comum).
    A semente tem de vir de um hash ESTAVEL."""
    import subprocess
    import sys

    from build_calibration_corpus import lang_seed

    runs = set()
    for _ in range(3):
        out = subprocess.run(
            [sys.executable, "-c",
             "import sys; sys.path.insert(0, r'D:/Projetos/SIG/tools/granite/nar');"
             "from build_calibration_corpus import lang_seed;"
             "print(lang_seed('pt_br', 20260829))"],
            capture_output=True, text=True, check=True).stdout.strip()
        runs.add(out)
    assert len(runs) == 1, f"semente variou entre processos: {runs}"
    assert int(runs.pop()) == lang_seed("pt_br", 20260829)


def test_lang_seed_differs_per_language():
    from build_calibration_corpus import LANGS, lang_seed

    seeds = [lang_seed(l, 20260829) for l in LANGS]
    assert len(set(seeds)) == len(LANGS)


# --------------------------------------------------------------------------- vacinas
# Regras que ja custaram uma rodada de medicao inteira. Cada teste aqui existe para
# quebrar o build no dia em que a regra for violada de novo.


def test_insertion_slots_interleave_blank_between_tokens():
    """A entrada do LLM e [blank, t0, blank, t1, ...], NAO [blank]*8 + tokens.

    Este e o bug de montagem que produziu texto corrompido no inicio e foi confundido
    com falha de quantizacao. Espelha `_add_insertion_slots` do modelo.
    """
    slots = common.build_insertion_slots([7, 9], blank=100257, min_edit=1)
    assert slots == [100257, 7, 100257, 9, 100257]


def test_insertion_slots_never_start_with_token_run():
    """Vacina explicita contra a variante errada: blank de preenchimento ANTES dos tokens."""
    ctc = [11, 22, 33]
    slots = common.build_insertion_slots(ctc, blank=100257, min_edit=1)
    errado = [100257] * 8 + ctc
    assert slots != errado
    # a posicao de cada token e sempre impar
    for i, tok in enumerate(ctc):
        assert slots[2 * i + 1] == tok
    # todo indice par e blank
    assert all(slots[i] == 100257 for i in range(0, len(slots), 2))


def test_insertion_slots_respect_min_edit_length():
    """Sequencia curta e preenchida ate `min_edit_sequence_length` (constante 8 do plano)."""
    assert len(common.build_insertion_slots([], min_edit=8)) == 8
    assert len(common.build_insertion_slots([1], min_edit=8)) == 8
    assert len(common.build_insertion_slots([1, 2, 3], min_edit=8)) == 8
    # acima do minimo, vale 2n+1
    assert len(common.build_insertion_slots(list(range(10)), min_edit=8)) == 21


def test_insertion_slots_matches_model_formula():
    """Referencia cruzada com a formula do modeling_granite_speech_nar._add_insertion_slots."""
    for n in range(0, 30):
        ctc = list(range(100, 100 + n))
        slots = common.build_insertion_slots(ctc, blank=0, min_edit=8)
        assert len(slots) == max(2 * n + 1, 8)
        assert sum(1 for s in slots if s != 0) == n


def test_cer_zero_for_identical_text():
    assert common.cer("hello world", "hello world") == 0.0


def test_cer_ignores_case_punctuation_and_spacing():
    assert common.cer("Hello,  World!", "hello world") == 0.0


def test_cer_counts_a_single_substitution():
    # 1 caractere errado em 5 = 0.2
    assert common.cer("helo!", "hello") == pytest.approx(1 / 5)


def test_cer_uses_external_reference_not_model_output():
    """A referencia tem que ser a do dataset.

    Caso real: o modelo (em qualquer precisao, inclusive fp32) transcreve
    'wifi door bell' como 'wi doorbell'. Comparar o quantizado contra a saida do
    proprio modelo conta esse erro do modelo como se fosse da quantizacao.
    """
    fleurs = "he built a wifi door bell he said"
    float_out = "he built a wi doorbell, he said."
    quant_out = "he built a wifi-fi dobell, he said."
    # contra a referencia externa: ambos erram, e o quantizado erra um pouco mais
    c_float, c_quant = common.cer(float_out, fleurs), common.cer(quant_out, fleurs)
    assert c_float > 0 and c_quant > c_float
    # contra a saida do float o quantizado parece "muito pior" (metrica enganosa)
    assert common.cer(quant_out, float_out) > c_quant


def test_quantized_bits_check_accepts_matching_bits():
    assert common.quantized_bits_check([4] * 281, 4) == ""
    assert common.quantized_bits_check([8] * 281, 8) == ""


def test_quantized_bits_check_detects_silently_ignored_bits():
    """O bug real: pedir 8 bits e o grafo sair com 4 (algo_config sem campo `bits`)."""
    erro = common.quantized_bits_check([4] * 281, 8)
    assert erro != ""
    assert "4" in erro and "8" in erro


def test_quantized_bits_check_rejects_mixed_and_empty():
    assert common.quantized_bits_check([4] * 200 + [8] * 81, 4) != ""
    assert common.quantized_bits_check([], 4) != ""


def _einsum_qcr_model(m: int, h: int, c: int, d: int, r: int):
    """Modelo ONNX minimo com o Einsum de atencao relativa `bmhcd,crd->bmhcr`."""
    import onnx
    from onnx import TensorProto, helper

    q = helper.make_tensor_value_info("q", TensorProto.FLOAT, [1, m, h, c, d])
    p = helper.make_tensor_value_info("P", TensorProto.FLOAT, [c, r, d])
    out = helper.make_tensor_value_info("out", TensorProto.FLOAT, [1, m, h, c, r])
    no = helper.make_node("Einsum", ["q", "P"], ["out"], equation="b m h c d , c r d -> b m h c r")
    g = helper.make_graph([no], "einsum_ref", [q, p], [out])
    return onnx.helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)])


def test_einsum_equacao_normaliza_espacos():
    import onnx
    from onnx import helper

    import einsum_to_matmul as e2m

    no = helper.make_node("Einsum", ["a", "b"], ["o"], equation="b m h c d , c r d -> b m h c r")
    assert e2m.equacao_do_no(no) == e2m.EINSUM_ALVO


def test_einsum_rejeita_equacao_errada():
    from onnx import helper

    import einsum_to_matmul as e2m

    no = helper.make_node("Einsum", ["a", "b"], ["o"], equation="ij,jk->ik")
    with pytest.raises(ValueError):
        e2m.decompor(no, {}, None)


def test_einsum_rejeita_batch_maior_que_um():
    from onnx import helper

    import einsum_to_matmul as e2m

    no = helper.make_node("Einsum", ["q", "P"], ["o"], equation="bmhcd,crd->bmhcr")
    shapes = {"q": [2, 3, 8, 20, 128], "P": [20, 20, 128]}
    with pytest.raises(ValueError):
        e2m.decompor(no, shapes, None)


def test_einsum_rejeita_P_incompativel():
    from onnx import helper

    import einsum_to_matmul as e2m

    no = helper.make_node("Einsum", ["q", "P"], ["o"], equation="bmhcd,crd->bmhcr")
    shapes = {"q": [1, 3, 8, 20, 128], "P": [21, 20, 128]}   # c=21 != 20
    with pytest.raises(ValueError):
        e2m.decompor(no, shapes, None)


def test_einsum_usa_shape_canonico_quando_o_inference_falha():
    """O shape inference nao propaga as camadas 1..N; o canonico da camada 0 vale para todas."""
    from onnx import helper

    import einsum_to_matmul as e2m

    no = helper.make_node("Einsum", ["q", "P"], ["o"], equation="bmhcd,crd->bmhcr")
    shapes = {"q": [None, None, None, None, None], "P": [200, 200, 128]}
    canon = [1, 4, 8, 200, 128]
    nos, inits = e2m.decompor(no, shapes, canon)
    # m=4 -> 4 Split outputs -> 4 ReshapeQ + 4 MatMul, mais 1 Split, 1 Transpose,
    # 2 Reshape (P e saida) e 1 Concat = 4+4+1+1+2+1 = 13
    assert len(nos) == 13
    assert sum(1 for n in nos if n.op_type == "MatMul") == 4
    assert sum(1 for n in nos if n.op_type == "Split") == 1


def test_einsum_decomposicao_tem_paridade_matematica():
    """Verificacao REAL: roda o modelo original e o decomposto no ORT e compara.

    NAO se exige bit-exatidao (medido: o proprio kernel Einsum do ORT NAO e bit-exato
    contra `np.einsum` — o MLAS acumula em ordem diferente, divergindo ja na 6a casa
    decimal em float32). O que se exige: (a) a decomposicao concorda com o numpy na
    tolerancia de float32 e (b) fica tao perto do Einsum original quanto o proprio
    original fica do valor exato — ou seja, a reescrita nao introduz erro adicional.
    """
    ort = pytest.importorskip("onnxruntime")
    import numpy as np
    import onnx

    import einsum_to_matmul as e2m

    m_, h_, c_, d_, r_ = 3, 2, 5, 7, 4
    original = _einsum_qcr_model(m_, h_, c_, d_, r_)

    # mesma estrutura -> passa pelo caminho real de conversao
    decomposto = _einsum_qcr_model(m_, h_, c_, d_, r_)
    trocados, falhas, _ = e2m.decompor_modelo(decomposto)
    assert trocados == 1 and not falhas

    rng = np.random.default_rng(20260910)
    q = rng.standard_normal((1, m_, h_, c_, d_)).astype(np.float32)
    P = rng.standard_normal((c_, r_, d_)).astype(np.float32)

    esperado = np.einsum("bmhcd,crd->bmhcr", q, P)

    so = ort.SessionOptions()
    so.log_severity_level = 3
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    res = {}
    for rotulo, modelo in (("orig", original), ("dec", decomposto)):
        import tempfile

        with tempfile.TemporaryDirectory() as td:
            caminho = Path(td) / f"{rotulo}.onnx"
            onnx.save(modelo, caminho)
            s = ort.InferenceSession(str(caminho), so, providers=["CPUExecutionProvider"])
            res[rotulo] = s.run(None, {"q": q, "P": P})[0]

    assert res["orig"].shape == esperado.shape
    assert res["dec"].shape == esperado.shape

    def erro_rel(a, b):
        return np.abs(a - b).max() / max(np.abs(b).max(), 1e-12)

    e_orig = erro_rel(res["orig"], esperado)
    e_dec = erro_rel(res["dec"], esperado)
    e_par = erro_rel(res["dec"], res["orig"])
    assert e_orig < 1e-5, f"o Einsum do ORT divergiu do numpy alem de fp32 ({e_orig:.2e})"
    assert e_dec < 1e-5, f"a decomposicao divergiu do numpy alem de fp32 ({e_dec:.2e})"
    assert e_par < 1e-5, f"decomposicao divergiu do original ({e_par:.2e})"
    # a reescrita nao pode ser pior que a implementacao nativa contra o valor exato
    assert e_dec <= max(e_orig * 10, 1e-7), (
        f"a decomposicao introduziu erro adicional: orig={e_orig:.2e} dec={e_dec:.2e}")


def test_fold_resumo_e_compara_ops():
    import collections

    import fold_constant_subgraphs as fcs

    antes = collections.Counter({"Constant": 10, "Mod": 1, "MatMul": 5})
    depois = collections.Counter({"Constant": 0, "MatMul": 5, "Add": 1})
    d = fcs.compara(antes, depois)
    assert d["Constant"] == {"antes": 10, "depois": 0}
    assert d["Mod"] == {"antes": 1, "depois": 0}
    assert "MatMul" not in d            # nao mudou: nao aparece
    assert "Add" not in d               # nao monitorado


def test_fold_remove_cadeia_de_constantes(tmp_path):
    """Cadeia de constantes (Add de 2 Constant -> alimenta Mul) deve sumir com basic."""
    ort = pytest.importorskip("onnxruntime")
    from onnx import TensorProto, helper, numpy_helper

    import fold_constant_subgraphs as fcs

    x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 4])
    y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, 4])
    a = numpy_helper.from_array(np.array([2.0], dtype=np.float32), "a")
    b = numpy_helper.from_array(np.array([3.0], dtype=np.float32), "b")
    c5 = numpy_helper.from_array(np.array([5.0], dtype=np.float32), "c5")
    nos = [
        helper.make_node("Add", ["a", "b"], ["soma"], name="Add_const"),
        helper.make_node("Add", ["soma", "c5"], ["const_total"], name="Add_const2"),
        helper.make_node("Mul", ["x", "const_total"], ["y"], name="Mul_runtime"),
    ]
    g = helper.make_graph(nos, "fold_test", [x], [y], [a, b, c5])
    modelo = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)])
    entrada = tmp_path / "in.onnx"
    saida = tmp_path / "out.onnx"
    import onnx

    onnx.save(modelo, str(entrada))

    antes = fcs.resumo_ops(entrada)
    assert antes.get("Add", 0) == 2
    fcs.otimizar(entrada, saida, "basic")
    depois = fcs.resumo_ops(saida)
    assert depois.get("Add", 0) == 0, f"constant folding nao removeu os Add: {depois}"
    assert depois.get("Mul", 0) == 1
    # contrato preservado
    mb = onnx.load(str(saida), load_external_data=False)
    assert [i.name for i in mb.graph.input] == ["x"]
    assert [o.name for o in mb.graph.output] == ["y"]


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
