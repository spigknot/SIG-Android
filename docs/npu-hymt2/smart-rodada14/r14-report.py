import subprocess
files = ["ggml-hexagon.cpp","htp/hmx-mm-kernels-tiled.h","htp/htp-ops.h","htp/hvx-mm-kernels-flat.h","htp/hvx-mm-kernels-tiled.h","htp/matmul-ops.c","htp/matmul-ops.h"]
for f in files:
    t = open("/root/kq-sig-work/"+f, encoding="utf-8", errors="replace").read()
    print(f"{f}: markers<<={t.count('<<<<<<<')} kq_markers={t.count('>>>>>>> kq')} sig_markers={t.count('>>>>>>> sig')} linhas={len(t.splitlines())}")
import hashlib
for f in ["htp/htp-ops.h","htp/matmul-ops.h"]:
    a = open("/root/kq-sig-work/"+f,'rb').read(); b = open("/root/sig-smart/llama/ggml/src/ggml-hexagon/"+f,'rb').read()
    print(f, "igual_ao_SIG=", hashlib.md5(a).hexdigest()==hashlib.md5(b).hexdigest(), "md5=", hashlib.md5(a).hexdigest()[:12])
