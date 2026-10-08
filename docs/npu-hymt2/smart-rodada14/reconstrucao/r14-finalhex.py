#!/usr/bin/env python3
# R14: APLICACAO FINAL dos pendentes do ggml-hexagon.cpp
import re
p = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(p).read()
lines = t.splitlines()

# (a) remover o case Q6_K mal-colado dentro do switch(op) do supports (4243-4245)
alvo_mal = """        case GGML_TYPE_Q6_K:
            repack_q6_K_tiled(tensor, data, 0, size);
            break;
"""
# so remover a ocorrencia na regiao do supports_op (a UNICA identada com 8 espacos no meio do switch(op))
i_mal = t.find("        case GGML_TYPE_PAD:\n            supp = ggml_hexagon_supported_pad(sess, op);\n            break;\n\n" + alvo_mal)
print("mal-colado encontrado:", i_mal > 0)
if i_mal > 0:
    t = t.replace(alvo_mal, "", 1)

# (b) inserir as funcoes do hunk[0] antes de "static void repack_tiled_q4_0"
def parse_hunks(path):
    hunks, cur = [], None
    for raw in open(path, encoding="utf-8", errors="replace").read().splitlines():
        if raw.startswith("@@"):
            cur = []; hunks.append(cur); continue
        if cur is None: continue
        if raw.startswith("+++") or raw.startswith("---"): continue
        if raw.startswith("+") or raw.startswith("-") or raw.startswith(" ") or raw=="":
            cur.append(raw)
    return hunks
hunks = parse_hunks("/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp.rej")
bloco0 = [x[1:] for x in hunks[0] if x.startswith("+")]
print("hunk0: linhas + =", len(bloco0), "| 1a:", bloco0[0][:60] if bloco0 else "-")
anchor = "static void repack_tiled_q4_0(void * data, const ggml_tensor * t, size_t size) {"
assert anchor in t, "ancora repack_tiled_q4_0 nao achada"
t = t.replace(anchor, "\n".join(bloco0) + "\n\n" + anchor, 1)

# (c) case Q6_K no switch 1 (apos o MXFP4, antes do default)
velho_c = """        case GGML_TYPE_MXFP4:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_tiled_mxfp4(data, tensor, size);
            break;

        default:
            memcpy(data, (const char *) tensor->data + offset, size);
            break;"""
novo_c = """        case GGML_TYPE_MXFP4:
            GGML_ASSERT(offset == 0);
            GGML_ASSERT(offset + size <= ggml_nbytes(tensor));
            repack_tiled_mxfp4(data, tensor, size);
            break;

        case GGML_TYPE_Q6_K:
            repack_q6_K_tiled(tensor, data, 0, size);
            break;

        default:
            memcpy(data, (const char *) tensor->data + offset, size);
            break;"""
assert t.count(velho_c) == 1, ("case Q6_K ctx", t.count(velho_c))
t = t.replace(velho_c, novo_c, 1)

# (d) [13] os 2 flat_src1_row_size
n13 = 0
for _ in range(4):
    t2 = re.sub(r"\(wtype == GGML_TYPE_Q4_1\)( \? htp_mm_q8_1_flat_row_size)", r"(wtype == GGML_TYPE_Q4_1 || wtype == GGML_TYPE_Q4_K)\1", t, count=1)
    if t2 == t: break
    t = t2; n13 += 1
print("flat_src1 fixes:", n13)

open(p, "w").write(t)
# verificacoes
c = open(p).read()
print("case Q4_K:", c.count("case GGML_TYPE_Q4_K:"), "| case Q6_K:", c.count("case GGML_TYPE_Q6_K:"))
print("repack_q6_K_tiled defs/chamadas:", c.count("repack_q6_K_tiled"), "| repack_q4_K_tiled:", c.count("repack_q4_K_tiled"))
print("q6_K_get_quant:", c.count("q6_K_get_quant"))
print("tamanho:", len(c))
print("R14_FINAL_HEX_DONE")
