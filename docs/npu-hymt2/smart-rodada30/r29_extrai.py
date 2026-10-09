#!/usr/bin/env python3
# r29_extrai.py — R29: extracao melhorada (§3+§4!)
# (a) get_scale_min_k4 EXTRAIDO AUTOMATICAMENTE do ggml-quants.c real (+diff pin!);
# (b) o CORPO COMPLETO de set_tensor (por assinatura! nao "primeiro switch"!);
# (c) hashes + fonte/linha; falha se nao achar!
import hashlib, re, sys

# ---------- (a) get_scale_min_k4 do ggml-quants.c ----------
Q = "/root/llama-cpp-npu/ggml/src/ggml-quants.c"
qt = open(Q, "rb").read().replace(b"\r\n", b"\n").decode()
ql = qt.split("\n")
for i, ln in enumerate(ql):
    if "static inline void get_scale_min_k4(" in ln:
        j = i
        depth = 0; started = False
        for j in range(i, min(i+20, len(ql))):
            for ch in ql[j]:
                if ch == "{": depth += 1; started = True
                elif ch == "}": depth -= 1
            if started and depth == 0: break
        helper = "\n".join(ql[i:j+1])
        print("# get_scale_min_k4: L%d..%d sha=%s" % (i+1, j+1, hashlib.sha256(helper.encode()).hexdigest()[:16]))
        break
else:
    print("HELPER NAO ACHADO!", file=sys.stderr); sys.exit(1)

# ---------- (b) set_tensor COMPLETO do candidato ----------
S = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
st = open(S, "rb").read().replace(b"\r\n", b"\n").decode()
sl = st.split("\n")
for i, ln in enumerate(sl):
    # a ASSINATURA exata (com "static void ... set_tensor("!)
    if re.search(r"^static void ggml_backend_hexagon_buffer_set_tensor\(", ln):
        j = i
        depth = 0; started = False
        for j in range(i, len(sl)):
            for ch in sl[j]:
                if ch == "{": depth += 1; started = True
                elif ch == "}": depth -= 1
            if started and depth == 0: break
        settensor = "\n".join(sl[i:j+1])
        print("# set_tensor COMPLETO: L%d..%d sha=%s (%d linhas!)" % (i+1, j+1, hashlib.sha256(settensor.encode()).hexdigest()[:16], j-i+1))
        break
else:
    print("SET_TENSOR NAO ACHADO!", file=sys.stderr); sys.exit(1)

# ---------- (c) as outras funcoes (do R28!) ----------
def extrai_funcao(lines, assinatura_re, nome):
    for i, ln in enumerate(lines):
        if re.search(assinatura_re, ln):
            depth = 0; started = False; out = []
            for j in range(i, len(lines)):
                out.append(lines[j])
                for ch in lines[j]:
                    if ch == "{": depth += 1; started = True
                    elif ch == "}": depth -= 1
                if started and depth == 0: break
            body = "\n".join(out)
            print(f"# {nome}: L{i+1}..{j+1} sha={hashlib.sha256(body.encode()).hexdigest()[:16]}")
            return body
    print(f"# {nome}: NAO ACHADO!", file=sys.stderr)
    return None

parts = [("get_scale_min_k4 (ggml-quants.c!)", helper, 0, 0)]
for pat, nome in [
    (r"static inline uint8_t q6_K_get_quant\(", "q6_K_get_quant"),
    (r"static inline bool ggml_hexagon_is_repack_type\(", "is_repack_type"),
    (r"static inline size_t ggml_hexagon_tiled_row_size\(", "tiled_row_size"),
    (r"static void repack_q4_K_tiled\(", "repack_q4_K_tiled"),
    (r"static void repack_q6_K_tiled\(", "repack_q6_K_tiled"),
    (r"static size_t ggml_backend_hexagon_buffer_type_get_alloc_size\(", "get_alloc_size"),
]:
    b = extrai_funcao(sl, pat, nome)
    assert b, "funcao requerida nao encontrada: " + nome
    parts.append((nome, b, 0, 0))

# ---------- gerar o header ----------
h = []
h.append("// r29_cores_gen.h — GERADO AUTOMATICAMENTE por r29_extrai.py (NAO EDITAR!)")
h.append("// Fontes: %s e %s" % (S, Q))
h.append("// hashes: fonte-hex=%s fonte-quants=%s" % (hashlib.sha256(st.encode()).hexdigest(), hashlib.sha256(qt.encode()).hexdigest()))
h.append("#pragma once")
h.append("")
for nome, body, a, b in parts:
    h.append(f"// ==== {nome} ====")
    h.append(body)
    h.append("")
gen = "\n".join(h)
open("/root/r29_cores_gen.h", "w").write(gen)
# o set_tensor COMPLETO num .inc separado (adaptado so' no wrapper!)
open("/root/r29_settensor_gen.inc", "w").write(
    "// r29_settensor_gen.inc — CORPO COMPLETO do set_tensor (L%d..L%d do fonte!)\n" % (0, 0) + settensor)
print("# gerado: r29_cores_gen.h + r29_settensor_gen.inc (set_tensor COMPLETO!)")
print("# sha256 do gen: %s" % hashlib.sha256(gen.encode()).hexdigest())
