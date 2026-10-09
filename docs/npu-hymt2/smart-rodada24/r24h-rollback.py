#!/usr/bin/env python3
# R24h: rollback EXATO para o estado R23 (fix2!) — teste A/B do UNWRITTEN!
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    i4 = t.find("        case GGML_TYPE_Q4_K:")
    i6 = t.find("        case GGML_TYPE_Q6_K:")
    idef = t.find("        default:", i6)
    assert i4 > 0 and i6 > i4 and idef > i6
    novo4 = """        case GGML_TYPE_Q4_K:
            fprintf(stderr, "KQSET: q4_K name=%s size=%zu -> repack_q4_K_tiled""" + BSN + """", tensor->name, size);
            repack_q4_K_tiled(tensor, data, 0, size);
            break;

"""
    novo6 = """        case GGML_TYPE_Q6_K:
            fprintf(stderr, "KQSET: q6_K name=%s size=%zu -> repack_q6_K_tiled""" + BSN + """", tensor->name, size);
            repack_q6_K_tiled(tensor, data, 0, size);
            break;

"""
    t = t[:i4] + novo4 + t[i6:]
    i6b = t.find("        case GGML_TYPE_Q6_K:")
    idef2 = t.find("        default:", i6b)
    t = t[:i6b] + novo6 + t[idef2:]
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "R24h rollback ok")
