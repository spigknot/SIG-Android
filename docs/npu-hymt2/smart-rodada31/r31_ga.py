#!/usr/bin/env python3
# r31_guard_ativa.py: GGML_ABORT ativo nos guards (R31 §4!)
Q = chr(34)
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    # Q4_K
    old4 = ('            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                fprintf(stderr, ' + Q + 'KQSET-RECUSA: q4_K offset=%zu size=%zu nbytes=%zu (contrato full-only!)' + BSN + Q + ',\n'
            '                        offset, size, (size_t) ggml_nbytes(tensor));\n'
            '                break;\n'
            '            }')
    new4 = ('            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                // R31: REJEICAO ATIVA do contrato full-only (mecanismo do backend\n'
            '                // - GGML_ABORT ativo, NAO assert C removivel!); aborta ANTES de\n'
            '                // qualquer leitura/escrita; o caller NAO continua com peso invalido!\n'
            '                GGML_ABORT(' + Q + 'KQSET: contrato full-only violado (q4_K): offset=%zu size=%zu nbytes=%zu' + BSN + Q + ',\n'
            '                           offset, size, (size_t) ggml_nbytes(tensor));\n'
            '            }')
    n4 = t.count(old4)
    if n4 == 1: t = t.replace(old4, new4, 1)
    # Q6_K
    old6 = ('            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                fprintf(stderr, ' + Q + 'KQSET-RECUSA: q6_K offset=%zu size=%zu nbytes=%zu (contrato full-only!)' + BSN + Q + ',\n'
            '                        offset, size, (size_t) ggml_nbytes(tensor));\n'
            '                break;\n'
            '            }')
    new6 = ('            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                // R31: REJEICAO ATIVA (idem Q4_K!)\n'
            '                GGML_ABORT(' + Q + 'KQSET: contrato full-only violado (q6_K): offset=%zu size=%zu nbytes=%zu' + BSN + Q + ',\n'
            '                           offset, size, (size_t) ggml_nbytes(tensor));\n'
            '            }')
    n6 = t.count(old6)
    if n6 == 1: t = t.replace(old6, new6, 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "guard-ativa: q4=", n4, "q6=", n6)
