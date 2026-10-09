#!/usr/bin/env python3
# r32_m6b.py: M6 por LINHAS (o silent-refusal!)
NL = chr(10)
inc = open("/root/r31_t/green/r29_settensor_adap.inc").read()
lines = inc.splitlines()
out = []
i = 0
n = 0
while i < len(lines):
    ln = lines[i]
    if "GGML_ABORT(" in ln and "q4_K" in ln:
        out.append('                fprintf(stderr, "KQSET-RECUSA(q4_K): silent-refusal! (M6!)'); out.append('"); break;')
        i += 2
        n += 1
        continue
    if "GGML_ABORT(" in ln and "q6_K" in ln:
        out.append('                fprintf(stderr, "KQSET-RECUSA(q6_K): silent-refusal! (M6!)'); out.append('"); break;')
        i += 2
        n += 1
        continue
    out.append(ln); i += 1
assert n == 2, "M6: %d substituicoes (esperado 2!)" % n
m6 = NL.join(out)
assert "GGML_ABORT" not in m6, "M6: ainda tem GGML_ABORT!"
open("/root/r32_t/m6/r29_settensor_adap.inc", "w").write(m6)
print("M6 ok (2 substituicoes — silent-refusal!)")
