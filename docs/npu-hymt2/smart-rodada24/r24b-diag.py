#!/usr/bin/env python3
# R24b: asserts -> DIAGNOSTICO com valores (sem abort! o contrato e' recusar parcial!)
K = "/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
K2 = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
for path in [K, K2]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    old = """        case GGML_TYPE_Q4_K:
            GGML_ASSERT(offset == 0 && "KQ set_tensor: pack exige o tensor inteiro (offset=0)!");
            GGML_ASSERT(size == ggml_nbytes(tensor) && "KQ set_tensor: upload parcial nao suportado!");
            fprintf(stderr, "KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled\\n", tensor->name, size);
            repack_q4_K_tiled(tensor, data, offset, size);
            break;

        case GGML_TYPE_Q6_K:
            GGML_ASSERT(offset == 0 && "KQ set_tensor: pack exige o tensor inteiro (offset=0)!");
            GGML_ASSERT(size == ggml_nbytes(tensor) && "KQ set_tensor: upload parcial nao suportado!");
            fprintf(stderr, "KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled\\n", tensor->name, size);
            repack_q6_K_tiled(tensor, data, offset, size);
            break;"""
    new = """        case GGML_TYPE_Q4_K:
            // R24: guard de contrato SEM abort: log dos valores reais e recusa
            // de upload parcial ANTES de ler/escrever! (o pack exige o tensor inteiro!)
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                fprintf(stderr, "KQSET-RECUSA: q4_K name=%s offset=%zu size=%zu nbytes=%zu (upload parcial!)\n",
                        tensor->name, offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            fprintf(stderr, "KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled\n", tensor->name, size);
            repack_q4_K_tiled(tensor, data, offset, size);
            break;

        case GGML_TYPE_Q6_K:
            if (offset != 0 || size != ggml_nbytes(tensor)) {
                fprintf(stderr, "KQSET-RECUSA: q6_K name=%s offset=%zu size=%zu nbytes=%zu (upload parcial!)\n",
                        tensor->name, offset, size, (size_t) ggml_nbytes(tensor));
                break;
            }
            fprintf(stderr, "KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled\n", tensor->name, size);
            repack_q6_K_tiled(tensor, data, offset, size);
            break;"""
    assert t.count(old) == 1, path + " cnt=" + str(t.count(old))
    t = t.replace(old, new, 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R24b ok")
