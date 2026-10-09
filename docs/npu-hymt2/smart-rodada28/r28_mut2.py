#!/usr/bin/env python3
# r28_mut2.py: mutantes por LINHAS (robusto!)
G = "/root/r28_switch_gen.inc"
A = "/root/r28_cores_gen.h"

def remove_case(lines, case_tag):
    out = []
    i = 0
    removed = 0
    while i < len(lines):
        if lines[i].strip() == ("case GGML_TYPE_%s:" % case_tag):
            # subir sobre o bloco de comentario acima (se houver!)
            while removed >= 0 and out and out[-1].strip().startswith("//"):
                out.pop()
            # pular ate' o "break;" (inclusive!) + a linha vazia!
            while i < len(lines) and lines[i].strip() != "break;":
                i += 1
            i += 1   # o break!
            if i < len(lines) and lines[i].strip() == "":
                i += 1   # a linha vazia!
            removed += 1
            continue
        out.append(lines[i])
        i += 1
    return out, removed

t = open(G).read().split("\n")
# M2: SO o Q6!
m2, n = remove_case(list(t), "Q6_K")
assert n == 1, "M2: removidos=%d" % n
open("/root/r28_switch_gen.m2.inc", "w").write("\n".join(m2))
# M1: Q4 E Q6!
m1, n1 = remove_case(list(t), "Q4_K")
m1, n2 = remove_case(m1, "Q6_K")
assert n1 == 1 and n2 == 1, "M1: %d/%d" % (n1, n2)
open("/root/r28_switch_gen.m1.inc", "w").write("\n".join(m1))
# M3: alloc lista!
h = open(A).read().split("\n")
done = False
for i, ln in enumerate(h):
    if "if (ggml_hexagon_is_repack_type(t->type))" in ln:
        h[i] = "    if (t->type == GGML_TYPE_Q4_0 || t->type == GGML_TYPE_Q4_1 || t->type == GGML_TYPE_Q8_0 || t->type == GGML_TYPE_IQ4_NL || t->type == GGML_TYPE_MXFP4) {   // MUTANTE lista sem K!"
        done = True
        break
assert done, "M3: linha nao achada!"
open("/root/r28_cores_gen.m3.h", "w").write("\n".join(h))
print("MUTANTES OK: m1 (sem Q4+Q6), m2 (sem Q6 SO!), m3 (alloc lista!)")
