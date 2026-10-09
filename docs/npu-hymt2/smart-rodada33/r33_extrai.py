#!/usr/bin/env python3
# r33_extrai.py: extrai o TRECHO do fluxo do probe.cpp REAL (ancoras + hash!)
import hashlib

P = "/root/probe-r33.cpp"  # copia do probe atual
t = open(P, "rb").read().replace(b"\r\n", b"\n").decode()
lines = t.split("\n")

# ancoras do bloco do alloc (R22 ALLOC_FAIL + o if(!bw)!):
i0 = None; i1 = None
for i, ln in enumerate(lines):
    if "R22 ALLOC_FAIL fail-closed" in ln:
        i0 = i
    if i0 is not None and "return false;" in ln and i > i0 + 5:
        i1 = i
        break
assert i0 and i1, "bloco do alloc nao achado! (i0=%s i1=%s)" % (i0, i1)
# incluir o contexto (o if (is_htp && buft_repack)!) — subir ate' ele!
j0 = i0
for j in range(i0, max(i0-12, 0), -1):
    if "if (is_htp && buft_repack)" in lines[j]:
        j0 = j
        break
bloco = "\n".join(lines[j0:i1+1])
print("# bloco do alloc: L%d..%d sha=%s (%d linhas!)" % (j0+1, i1+1, hashlib.sha256(bloco.encode()).hexdigest()[:16], i1-j0+1))
open("/root/r33_alloc_gen.inc", "w").write(
    "// GERADO — trecho REAL do probe (L%d..%d!), adaptado nos limites (declarado!): rep->printf, ggml_free->fake_free!\n" % (j0+1, i1+1) + bloco + "\n")

# as flags do opstage (htp-ops.h real!)
H = "/root/kq2/ggml/src/ggml-hexagon/htp/htp-ops.h"
h = open(H, "rb").read().decode()
import re
m = re.search(r"#define HTP_OPSTAGE_QUEUE\s+(\S+)", h)
m2 = re.search(r"#define HTP_OPSTAGE_COMPUTE\s+(\S+)", h)
assert m and m2, "flags do opstage nao achadas!"
print("# htp-ops.h: QUEUE=%s COMPUTE=%s (REAIS!)" % (m.group(1), m2.group(1)))
# a linha do uso (o candidato!)
S = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
s = open(S, "rb").read().decode()
for i, ln in enumerate(s.split("\n")):
    if "HTP_OPSTAGE_QUEUE" in ln and "opt_opstage" in ln:
        print("# uso real: L%d: %s" % (i+1, ln.strip()[:100]))
        break
print("EXTRACAO R33 OK")
