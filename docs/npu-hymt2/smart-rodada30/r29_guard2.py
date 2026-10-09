#!/usr/bin/env python3
# r29_guard2.py (v2 - strings literais, sem format!)
BSN = chr(92) + "n"
Q = chr(34)  # aspas!

for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    changed = 0
    old4 = ('        case GGML_TYPE_Q4_K:\n'
            '            fprintf(stderr, ' + Q + 'KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled' + BSN + Q + ', tensor->name ? tensor->name : ' + Q + '?' + Q + ', size);\n'
            '            repack_q4_K_tiled(tensor, data, 0, size);\n'
            '            break;')
    new4 = ('        case GGML_TYPE_Q4_K:\n'
            '            // R29: guarda full-only do contrato (offset=0 E size completo!);\n'
            '            // recusa ANTES de ler/escrever; NUNCA normalizar offset silenciosamente!\n'
            '            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                fprintf(stderr, ' + Q + 'KQSET-RECUSA: q4_K offset=%zu size=%zu nbytes=%zu (contrato full-only!)' + BSN + Q + ',\n'
            '                        offset, size, (size_t) ggml_nbytes(tensor));\n'
            '                break;\n'
            '            }\n'
            '            fprintf(stderr, ' + Q + 'KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled' + BSN + Q + ', tensor->name ? tensor->name : ' + Q + '?' + Q + ', size);\n'
            '            repack_q4_K_tiled(tensor, data, offset, size);\n'
            '            break;')
    if t.count(old4) == 1:
        t = t.replace(old4, new4, 1); changed += 1
    else:
        print(path, "Q4: count=", t.count(old4))
    old6 = ('        case GGML_TYPE_Q6_K:\n'
            '            fprintf(stderr, ' + Q + 'KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled' + BSN + Q + ', tensor->name ? tensor->name : ' + Q + '?' + Q + ', size);\n'
            '            repack_q6_K_tiled(tensor, data, 0, size);\n'
            '            break;')
    new6 = ('        case GGML_TYPE_Q6_K:\n'
            '            // R29: guarda full-only (idem Q4_K!)\n'
            '            if (offset != 0 || size != (size_t) ggml_nbytes(tensor)) {\n'
            '                fprintf(stderr, ' + Q + 'KQSET-RECUSA: q6_K offset=%zu size=%zu nbytes=%zu (contrato full-only!)' + BSN + Q + ',\n'
            '                        offset, size, (size_t) ggml_nbytes(tensor));\n'
            '                break;\n'
            '            }\n'
            '            fprintf(stderr, ' + Q + 'KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled' + BSN + Q + ', tensor->name ? tensor->name : ' + Q + '?' + Q + ', size);\n'
            '            repack_q6_K_tiled(tensor, data, offset, size);\n'
            '            break;')
    if t.count(old6) == 1:
        t = t.replace(old6, new6, 1); changed += 1
    else:
        print(path, "Q6: count=", t.count(old6))
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "changed:", changed)
