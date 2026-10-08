#!/usr/bin/env python3
# R14 fase A: opcodes + guards (supports permanece FALSE via #ifdef)
S = "/root/kq-sig-work"

# A.1 htp-ops.h: +2 opcodes (dentro do enum, padrao exato do delta)
p = S + "/htp/htp-ops.h"
t = open(p).read()
old = "    HTP_TYPE_Q4_1   = 3,\n    HTP_TYPE_Q8_0   = 8,\n"
assert t.count(old) == 1, ("htp-ops pattern", t.count(old))
new = ("    HTP_TYPE_Q4_1   = 3,\n    HTP_TYPE_Q8_0   = 8,\n"
       "    HTP_TYPE_Q4_K   = 12,   // R14 fase A (packet 1ec818809)\n"
       "    HTP_TYPE_Q6_K   = 14,   // R14 fase A (packet 1ec818809)\n")
open(p, "w").write(t.replace(old, new, 1))
print("A.1 htp-ops.h: +2 opcodes OK")

# A.2 ggml-hexagon.cpp: o #define do gate (no inicio, apos os includes)
p = S + "/ggml-hexagon.cpp"
t = open(p).read()
marca = "#pragma clang diagnostic ignored"
idx = t.find(marca)
assert idx > 0
gate = ("// R14 fase A: gate do suporte K-quant (Q4_K/Q6_K). Habilita SOMENTE\n"
        "// quando kernels+repack+dispatch estiverem completos e validados.\n"
        "#ifndef GGML_HEXAGON_KQ_ENABLED\n#define GGML_HEXAGON_KQ_ENABLED 0\n#endif\n\n")
t = t[:idx] + gate + t[idx:]
# os guards: nos 2 switches (supported_mul_mat / mul_mat_id): apos os cases MXFP4,
# inserir os cases Q4_K/Q6_K SOB o gate
alvo = ("        case GGML_TYPE_IQ4_NL:\n"
        "        case GGML_TYPE_MXFP4:\n")
n = t.count(alvo)
print("ocorrencias do bloco IQ4_NL+MXFP4:", n)
ins = (alvo +
       "#if GGML_HEXAGON_KQ_ENABLED\n"
       "        case GGML_TYPE_Q4_K:\n"
       "        case GGML_TYPE_Q6_K:\n"
       "#endif\n")
t = t.replace(alvo, ins)
open(p, "w").write(t)
print("A.2 ggml-hexagon.cpp: gate + cases sob #if (n=%d) OK" % n)
