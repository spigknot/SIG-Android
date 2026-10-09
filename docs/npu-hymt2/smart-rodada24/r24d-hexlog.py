#!/usr/bin/env python3
# R24d: log via HEX_VERBOSE (vai pro logcat!) com os numeros REAIS antes do guard
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    # Q4_K: log SEMPRE + guard
    old4 = '''        case GGML_TYPE_Q4_K:
            // R24: guard de contrato SEM abort: log dos valores reais e recusa
            // de upload parcial ANTES de ler/escrever! (o pack exige o tensor inteiro!)
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                fprintf(stderr, "KQSET-RECUSA: q4_K name=%s offset=%zu size=%zu nbytes=%zu (upload parcial!)''' + BSN + '''",
                        tensor->name, offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            fprintf(stderr, "KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled''' + BSN + '''", tensor->name, size);
            repack_q4_K_tiled(tensor, data, offset, size);
            break;'''
    new4 = '''        case GGML_TYPE_Q4_K:
            // R24: log via HEX_VERBOSE (vai pro logcat!) com os numeros REAIS!
            HEX_VERBOSE("KQSET q4_K: offset=%zu size=%zu nbytes=%zu ne0=%lld ne1=%lld nb1=%zu (canon=%zu)"''' + BSN + ''',
                        offset, size, (size_t) ggml_nbytes(tensor),
                        (long long) tensor->ne[0], (long long) tensor->ne[1], (size_t) tensor->nb[1],
                        (size_t) (ggml_row_size(tensor->type, tensor->ne[0]) * tensor->ne[1] * tensor->ne[2] * tensor->ne[3]));
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                HEX_VERBOSE("KQSET-RECUSA q4_K: upload parcial rejeitado!''' + BSN + '''");
                break;
            }
            repack_q4_K_tiled(tensor, data, offset, size);
            break;'''
    assert t.count(old4) == 1, path + " q4k cnt=" + str(t.count(old4))
    t = t.replace(old4, new4, 1)
    # Q6_K
    old6 = '''        case GGML_TYPE_Q6_K:
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                fprintf(stderr, "KQSET-RECUSA: q6_K name=%s offset=%zu size=%zu nbytes=%zu (upload parcial!)''' + BSN + '''",
                        tensor->name, offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            fprintf(stderr, "KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled''' + BSN + '''", tensor->name, size);
            repack_q6_K_tiled(tensor, data, offset, size);
            break;'''
    new6 = '''        case GGML_TYPE_Q6_K:
            HEX_VERBOSE("KQSET q6_K: offset=%zu size=%zu nbytes=%zu ne0=%lld ne1=%lld nb1=%zu"''' + BSN + ''',
                        offset, size, (size_t) ggml_nbytes(tensor),
                        (long long) tensor->ne[0], (long long) tensor->ne[1], (size_t) tensor->nb[1]);
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                HEX_VERBOSE("KQSET-RECUSA q6_K: upload parcial rejeitado!''' + BSN + '''");
                break;
            }
            repack_q6_K_tiled(tensor, data, offset, size);
            break;'''
    assert t.count(old6) == 1, path + " q6k cnt=" + str(t.count(old6))
    t = t.replace(old6, new6, 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R24d ok")
