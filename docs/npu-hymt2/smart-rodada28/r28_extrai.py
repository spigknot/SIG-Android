#!/usr/bin/env python3
# r28_extrai.py — R28: EXTRACAO AUTOMATICA VERBATIM dos callbacks/helpers REAIS
# do candidato (kq2/ggml-hexagon.cpp!) -> gera r28_cores_gen.h (com hashes!)
# Nada transcrito a mao: o conteudo e' COPIADO byte-a-byte do fonte por script!
import hashlib, re, sys

SRC = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(SRC, "rb").read().replace(b"\r\n", b"\n").decode()
lines = t.split("\n")

def extrai_funcao(assinatura_re, nome):
    """Acha a linha com a assinatura e extrai a funcao INTEIRA (chaves balanceadas!)."""
    for i, ln in enumerate(lines):
        if re.search(assinatura_re, ln):
            # achar o inicio (a linha!) e o fim (o } balanceado!)
            depth = 0
            started = False
            out = []
            for j in range(i, len(lines)):
                out.append(lines[j])
                for ch in lines[j]:
                    if ch == "{": depth += 1; started = True
                    elif ch == "}": depth -= 1
                if started and depth == 0:
                    break
            body = "\n".join(out)
            print(f"# {nome}: L{i+1}..{j+1} ({j-i+1} linhas!) sha={hashlib.sha256(body.encode()).hexdigest()[:16]}")
            return body, i + 1, j + 1
    print(f"# {nome}: NAO ACHADO!", file=sys.stderr)
    return None, 0, 0

parts = []
for pat, nome in [
    (r"static inline uint8_t q6_K_get_quant\(", "q6_K_get_quant"),
    (r"static inline bool ggml_hexagon_is_repack_type\(", "is_repack_type"),
    (r"static inline size_t ggml_hexagon_tiled_row_size\(", "tiled_row_size"),
    (r"static void repack_q4_K_tiled\(", "repack_q4_K_tiled"),
    (r"static void repack_q6_K_tiled\(", "repack_q6_K_tiled"),
    (r"static size_t ggml_backend_hexagon_buffer_type_get_alloc_size\(", "get_alloc_size"),
]:
    body, a, b = extrai_funcao(pat, nome)
    if body:
        parts.append((nome, body, a, b))

# O SWITCH do set_tensor: extrair do "switch (tensor->type)" ate' o fecho do switch!
sw_i = None
for i, ln in enumerate(lines):
    if "switch (tensor->type)" in ln:
        sw_i = i
        break
assert sw_i is not None, "switch do set_tensor nao achado!"
# o switch pertence ao set_tensor (o PRIMEIRO 'switch(tensor->type)' e' o do set!)
depth = 0; started = False; sw_lines = []
for j in range(sw_i, len(lines)):
    sw_lines.append(lines[j])
    for ch in lines[j]:
        if ch == "{": depth += 1; started = True
        elif ch == "}": depth -= 1
    if started and depth == 0:
        break
switch_body = "\n".join(sw_lines)
print(f"# set_tensor switch: L{sw_i+1}..{j+1} sha={hashlib.sha256(switch_body.encode()).hexdigest()[:16]}")

# gerar o header com TODO o codigo verbatim + metadados!
h = []
h.append("// r28_cores_gen.h — GERADO AUTOMATICAMENTE por r28_extrai.py (NAO EDITAR!)")
h.append("// Conteudo VERBATIM do candidato: %s" % SRC)
h.append("// sha256 do fonte: %s" % hashlib.sha256(t.encode()).hexdigest())
h.append("#pragma once")
h.append("")
for nome, body, a, b in parts:
    h.append(f"// ==== {nome} (L{a}..L{b} do fonte!) ====")
    h.append(body)
    h.append("")
h.append(f"// ==== SWITCH do set_tensor (L{sw_i+1}..L{j+1} do fonte!) ====")
h.append("// (o switch sera' chamado pelo harness com um contexto minimo!)")
h.append(switch_body)
h.append("")
gen = "\n".join(h)
open("/root/r28_cores_gen.h", "w").write(gen)
print("# gerado: /root/r28_cores_gen.h (%d linhas!)" % len(gen.split(chr(10))))
print("# sha256 do gerado: %s" % hashlib.sha256(gen.encode()).hexdigest())
