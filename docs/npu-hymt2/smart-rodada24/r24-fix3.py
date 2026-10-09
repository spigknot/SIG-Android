#!/usr/bin/env python3
# R24 FIX 3: guard offset/size no set_tensor K + corrige warning name
K = "/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
K2 = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
for path in [K, K2]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    old = """        // R23 FIX: cases K-quant FALTAVAM no set_tensor (Q4_K caia no memcpy
        // CANONICO sem repack => DSP lia canonicos como tiled => NaN!)
        case GGML_TYPE_Q4_K:
            fprintf(stderr, "KQSET: q4_K name=%s offset=%zu size=%zu -> repack_q4_K_tiled\\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q4_K_tiled(tensor, data, 0, size);
            break;

        case GGML_TYPE_Q6_K:
            fprintf(stderr, "KQSET: q6_K name=%s offset=%zu size=%zu -> repack_q6_K_tiled\\n",
                    tensor->name ? tensor->name : "?", offset, size);
            repack_q6_K_tiled(tensor, data, 0, size);
            break;"""
    new = """        // R23/R24 FIX: cases K-quant FALTAVAM no set_tensor (Q4_K caia no
        // memcpy CANONICO sem repack => DSP lia canonicos como tiled => NaN!).
        // R24: guard EXPLICITO de offset/size (o pack exige tensor inteiro!);
        // recusa upload parcial ANTES de ler/escrever (contrato do pack!).
        case GGML_TYPE_Q4_K:
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
    assert t.count(old) == 1, path + " cnt=" + str(t.count(old))
    t = t.replace(old, new, 1)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "FIX3 ok")
