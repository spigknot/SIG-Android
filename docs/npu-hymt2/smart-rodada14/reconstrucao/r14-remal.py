#!/usr/bin/env python3
# R14: remove o case mal-colado + monta o tree sigcand2 + rebuild
import re
p = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(p).read()
# o mal-colado: 'case GGML_TYPE_Q6_K:' com repack dentro do switch(op) do supports.
# O texto exato (8 espacos) — ocorre 1x (o do switch de repack tem 8 espacos tambem!).
# Distinguir: o do switch repack esta perto de 'repack_tiled_mxfp4'; o mal esta perto de 'supported_pad'.
mal = """        case GGML_TYPE_Q6_K:
            repack_q6_K_tiled(tensor, data, 0, size);
            break;

        default:
            break;
    }

    ggml_hexagon_dump_op_supp(sess->name, op, supp);"""
if t.count(mal) == 1:
    t = t.replace(mal, """        default:
            break;
    }

    ggml_hexagon_dump_op_supp(sess->name, op, supp);""", 1)
    print("mal-colado removido")
else:
    print("mal-colado NAO achado (count=%d)" % t.count(mal))
open(p, "w").write(t)
c = open(p).read()
print("case Q6_K final:", c.count("case GGML_TYPE_Q6_K:"))
