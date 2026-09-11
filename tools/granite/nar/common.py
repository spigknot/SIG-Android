"""Shared helpers for Granite 4.1 NAR experiment tools.

Every tool accepts --work-dir (absolute), is resumable (hash/idempotence),
writes JSON reports, uses streaming SHA-256 and .partial + atomic rename.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

CHUNK = 4 * 1024 * 1024


def sha256_file(path: os.PathLike | str) -> str:
    """Streaming SHA-256; never reads the whole file into memory."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def atomic_write_bytes(path: os.PathLike | str, data: bytes) -> None:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(p.name + ".partial")
    with open(tmp, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, p)


def atomic_write_json(path: os.PathLike | str, obj) -> None:
    atomic_write_bytes(path, (json.dumps(obj, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def atomic_write_text(path: os.PathLike | str, text: str) -> None:
    atomic_write_bytes(path, text.encode("utf-8"))


def read_json(path: os.PathLike | str):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def now_iso() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


# ---------------------------------------------------------------- state ----

def state_path(work_dir: Path) -> Path:
    return work_dir / "state" / "run-state.json"


def load_state(work_dir: Path) -> dict:
    p = state_path(work_dir)
    if p.exists():
        try:
            return read_json(p)
        except Exception:
            pass
    return {"steps": {}}


def save_state(work_dir: Path, state: dict) -> None:
    state["updated_at"] = now_iso()
    atomic_write_json(state_path(work_dir), state)


def record_step(work_dir: Path, step: str, status: str, *, command: str = "",
                exit_code: int | None = None, artifacts: dict | None = None,
                error: str = "", started_at: str = "") -> dict:
    state = load_state(work_dir)
    steps = state.setdefault("steps", {})
    prev = steps.get(step, {})
    steps[step] = {
        "status": status,  # pending/running/passed/failed/skipped
        "started_at": prev.get("started_at", started_at or now_iso()),
        "finished_at": now_iso(),
        "command": command or prev.get("command", ""),
        "exit_code": exit_code,
        "artifacts": artifacts or prev.get("artifacts", {}),
        "error": error,
    }
    save_state(work_dir, state)
    return steps[step]


def step_status(work_dir: Path, step: str) -> str:
    return load_state(work_dir).get("steps", {}).get(step, {}).get("status", "pending")


# ------------------------------------------------------------- CLI base ----

def base_parser(description: str) -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(description=description)
    ap.add_argument("--work-dir", required=True,
                    help="Absolute experiment directory (E:/SIG-granite-nar-lab/<id>)")
    return ap


def work_dirs(work_dir: Path) -> dict:
    d = {name: (work_dir / name) for name in
         ("source", "cache", "venv", "exports", "golden", "calibration",
          "quantized", "packages", "logs", "reports", "state")}
    for p in d.values():
        p.mkdir(parents=True, exist_ok=True)
    return d


def env_for_experiment(work_dir: Path) -> dict:
    """HF/pip caches pinned INSIDE the experiment (override, não setdefault:
    um HF_HOME herdado do ambiente vazaría cache para fora do experimento)."""
    env = dict(os.environ)
    exp = str(work_dir.resolve())
    env["HF_HOME"] = os.path.join(exp, "cache", "hf")
    env["HF_HUB_CACHE"] = os.path.join(exp, "cache", "hf", "hub")
    env["TRANSFORMERS_CACHE"] = os.path.join(exp, "cache", "hf", "hub")
    env["PIP_CACHE_DIR"] = os.path.join(exp, "cache", "pip")
    env.setdefault("TMPDIR", os.path.join(exp, "cache", "tmp"))
    env["TORCH_HOME"] = os.path.join(exp, "cache", "torch")
    return env


# ------------------------------------------------------------ artifacts ----

def file_entry(path: Path, base: Path | None = None) -> dict:
    entry = {
        "path": str(path.relative_to(base)) if base else str(path),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }
    return entry


def looks_like_lfs_pointer(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            head = f.read(400)
    except OSError:
        return False
    return head.startswith(b"version https://git-lfs.github.com/spec/v1")


def run_cmd(cmd: list[str], log_path: Path, env: dict | None = None,
            cwd: Path | None = None, timeout: int = 3600) -> int:
    """Run a subprocess, tee stdout/stderr to log_path; return exit code."""
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with open(log_path, "ab") as log:
        log.write(f"\n==== {now_iso()} $ {' '.join(cmd)}\n".encode())
        log.flush()
        proc = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT,
                              env=env, cwd=str(cwd) if cwd else None, timeout=timeout)
    return proc.returncode


def print_step(msg: str) -> None:
    print(f"[nar] {time.strftime('%H:%M:%S')} {msg}", flush=True)


# --------------------------------------------------------------------------- regras
# Constantes congeladas do plano (nao mudar sem decisao explicita).
BLANK_TOKEN_ID = 100257
MIN_EDIT_SEQUENCE_LENGTH = 8


def build_insertion_slots(ctc_tokens, blank: int = BLANK_TOKEN_ID,
                          min_edit: int = MIN_EDIT_SEQUENCE_LENGTH) -> list[int]:
    """Sequencia de entrada do LLM editor: blank INTERCALADO com os tokens CTC.

    Espelha `_add_insertion_slots` do modelo (modeling_granite_speech_nar.py):

        total = max(2*n + 1, min_edit_sequence_length)
        slots = [blank] * total
        slots[2*i + 1] = ctc_tokens[i]

    Resultado para n=2:  [blank, t0, blank, t1, blank]
    NAO e `[blank]*8 + tokens` — essa variante produz texto corrompido no inicio
    (prefixo embaralhado) e se confunde com falha de quantizacao. A regra mora aqui
    para existir UMA implementacao; antes estava duplicada em tres arquivos.
    """
    n = len(ctc_tokens)
    total = max(2 * n + 1, min_edit)
    slots = [blank] * total
    for i, tok in enumerate(ctc_tokens):
        slots[2 * i + 1] = int(tok)
    return slots


def normalize_for_cer(text: str) -> str:
    """Normalizacao para comparacao de transcricao (case, acentos/pontuacao, espacos)."""
    s = unicodedata.normalize("NFKC", str(text)).lower()
    s = re.sub(r"[^\w\s]", " ", s, flags=re.UNICODE)
    return re.sub(r"\s+", " ", s).strip()


def edit_distance(a: str, b: str) -> int:
    """Distancia de Levenshtein (dois vetores, O(len(b)) de memoria)."""
    if len(a) < len(b):
        a, b = b, a
    anterior = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        atual = [i]
        for j, cb in enumerate(b, 1):
            atual.append(min(anterior[j] + 1, atual[j - 1] + 1,
                             anterior[j - 1] + (ca != cb)))
        anterior = atual
    return anterior[-1]


def cer(hypothesis: str, reference: str):
    """Character Error Rate: distancia de edicao / tamanho da referencia.

    Esta e a metrica que decide qualidade de quantizacao. Comparar transcricao por
    IGUALDADE EXATA e uma metrica binaria que reprova uma amostra por um unico
    caractere: no mesmo corpus, um artefato 4-bit que por CER empata com o float
    (0,0324 vs 0,0323) aparece com apenas 50% de acerto exato. Sempre reporte CER
    (e a distribuicao pareada), nunca so o exact-match, e sempre contra a
    transcricao de referencia do dataset — nunca contra a saida do proprio modelo,
    que ja erra palavras raras em fp32.
    """
    o, r = normalize_for_cer(hypothesis), normalize_for_cer(reference)
    if not r:
        return None
    return edit_distance(o, r) / len(r)


def normaliza_numeros(texto: str) -> str:
    """Converte DIGITOS para extenso (pt-BR) antes de comparar transcricoes.

    Motivo medido: o modelo escreve numeros em digitos ("457", "10 de setembro", "rua 7 de abril")
    enquanto as referencias de corpus usam EXTENSO ("quatrocentos e cinquenta e sete"). Comparar
    cru infla o CER com uma diferenca de FORMATO que **nao e erro de reconhecimento**. Num audio
    real gravado, TRES conversoes (457 -> 10 -> 7) respondiam por quase todo um CER de 0,2054: a
    transcricao estava correta e a metrica dizia que nao.

    Cobre 0..99999 (suficiente para fala espontanea). Numero fora da faixa fica como esta — melhor
    nao normalizar que normalizar errado.

    NAO esta embutida em `normalize_for_cer` de proposito: os CER historicos (0,0323 do float, etc.)
    foram medidos sem esta normalizacao, e mudar o default reescreveria numeros ja publicados.
    Use explicitamente onde a comparacao e com fala real.
    """
    if not texto:
        return texto

    unidades = ["zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
                "dez", "onze", "doze", "treze", "quatorze", "quinze", "dezesseis", "dezessete",
                "dezoito", "dezenove"]
    dezenas = {20: "vinte", 30: "trinta", 40: "quarenta", 50: "cinquenta", 60: "sessenta",
               70: "setenta", 80: "oitenta", 90: "noventa"}
    centenas = {100: "cento", 200: "duzentos", 300: "trezentos", 400: "quatrocentos",
                500: "quinhentos", 600: "seiscentos", 700: "setecentos", 800: "oitocentos",
                900: "novecentos"}

    def extenso(n: int) -> str:
        if n < 20:
            return unidades[n]
        if n == 100:
            return "cem"
        if n < 100:
            d, u = divmod(n, 10)
            return dezenas[d * 10] + (f" e {unidades[u]}" if u else "")
        if n < 1000:
            c, resto = divmod(n, 100)
            base = centenas[c * 100]
            return base + (f" e {extenso(resto)}" if resto else "")
        if n < 100000:
            mil, resto = divmod(n, 1000)
            base = "mil" if mil == 1 else f"{extenso(mil)} mil"
            if not resto:
                return base
            liga = " e " if resto < 100 or resto % 100 == 0 else " "
            return base + liga + extenso(resto)
        return str(n)

    # remove separador de milhar ("1.000" -> "1000") antes de converter
    s = re.sub(r"\b(\d{1,3})(?:\.(\d{3}))+\b", lambda m: m.group(0).replace(".", ""), texto)

    def troca(m: "re.Match[str]") -> str:
        try:
            n = int(m.group(0))
        except ValueError:
            return m.group(0)
        return extenso(n) if 0 <= n <= 99999 else m.group(0)

    return re.sub(r"\d+", troca, s)


def cer_normalizado(hypothesis: str, reference: str):
    """`cer` com numeros uniformizados (digitos -> extenso) dos DOIS lados.

    Use esta para fala real; `cer` continua sendo a metrica historica dos relatorios de corpus.
    """
    return cer(normaliza_numeros(hypothesis), normaliza_numeros(reference))


def quantized_bits_check(node_bits, requested: int) -> str:
    """Valida os bits REAIS gravados no grafo contra os pedidos.

    O `MatMulNBitsQuantizer` do ORT 1.29 so usa o parametro `bits` quando
    `algo_config is None`; passar um algo_config sem campo `bits` faz o quantizador
    cair no proprio default (4) e o pedido e silenciosamente ignorado — ja produziu
    uma rodada rotulada "int8" com artefatos 4-bit byte-identicos aos de 4 bits.
    Retorna "" quando ok, senao a mensagem de erro.
    """
    distintos = {int(b) for b in node_bits}
    if not distintos:
        return "nenhum no MatMulNBits encontrado no grafo"
    if distintos != {int(requested)}:
        return (f"pedido bits={requested} mas o grafo tem {sorted(distintos)} "
                f"({sum(1 for _ in node_bits)} nos)")
    return ""


# Limite por amostra: acima disso a variante nao degradou, ela QUEBROU. Foi o que reprovou o
# 2-bit (82/82 amostras acima de 0,30) enquanto o CER medio escondia o tamanho do estrago.
LIMITE_CER_AMOSTRA = 0.30


def grade_variant(hypotheses, references) -> dict:
    """Avalia uma variante devolvendo SEMPRE exact-match e CER juntos.

    Motivo (a licao que invalidou o encerramento da linha de quantizacao): exact-match e
    uma metrica BINARIA — um unico caractere errado reprova a amostra inteira. No mesmo
    corpus, um artefato 4-bit que por CER empata com o float (0,0324 vs 0,0323) aparece
    com 50% de acerto exato, e um 8-bit indistinguivel do float aparece com 58%. Lido
    sozinho, o exact-match sugere "metade quebrado" quando nada quebrou.

    Por isso esta funcao nao oferece o exact-match sozinho: quem julga uma variante
    recebe as duas metricas na mesma chamada e nao consegue mais decidir por uma so.

    Devolve: n, exact_match (fracao), cer_medio, cer_mediana, acima_do_LIMITE_CER_AMOSTRA
    e pior_cer. Referencias vazias sao descartadas (cer devolve None).
    """
    n = exatos = 0
    cers: list[float] = []
    for hip, ref in zip(hypotheses, references):
        valor = cer(hip, ref)
        if valor is None:
            continue
        n += 1
        cers.append(valor)
        if valor == 0.0:
            exatos += 1
    if n == 0:
        return {"n": 0, "exact_match": None, "cer_medio": None, "cer_mediana": None,
                "acima_de_0_30": 0, "pior_cer": None}
    return {
        "n": n,
        "exact_match": exatos / n,
        "cer_medio": sum(cers) / n,
        "cer_mediana": statistics.median(cers),
        "acima_de_0_30": sum(1 for c in cers if c > LIMITE_CER_AMOSTRA),
        "pior_cer": max(cers),
    }


_sessoes_abertas = 0


@contextlib.contextmanager
def single_session(factory, *args, **kwargs):
    """Abre UMA sessao ONNX pesada por vez no processo.

    Motivo: o encoder do NAR em T=2000 consome varios GB por sessao. Um roteiro que abria
    4 sessoes de encoder em paralelo chegou a 13 GB de RSS e o processo foi morto pelo
    sistema no meio — o resultado parecia "modelo travado" quando era falta de memoria.
    Uma sessao por vez e a regra; a comparacao entre variantes e SEQUENCIAL.

    A fabrica e injetada de proposito: assim o teste verifica a regra SEM carregar o ONNX
    Runtime (que nao existe no JVM de teste nem e necessario para provar a contagem).
    """
    global _sessoes_abertas
    if _sessoes_abertas:
        raise RuntimeError(
            f"ja existe {_sessoes_abertas} sessao(oes) ONNX aberta(s) neste processo; "
            "abra uma por vez (ver single_session) para nao estourar a memoria")
    sessao = factory(*args, **kwargs)
    _sessoes_abertas += 1
    try:
        yield sessao
    finally:
        _sessoes_abertas -= 1
        del sessao


def sessoes_abertas() -> int:
    """Quantas sessoes pesadas estao abertas agora (0 = nada vazando)."""
    return _sessoes_abertas
