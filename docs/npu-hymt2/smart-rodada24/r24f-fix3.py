#!/usr/bin/env python3
# R24f: fix padrao geral  "\n,  =>  \n",
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    bad = chr(34) + BSN + ","
    good = BSN + chr(34) + ","
    n = t.count(bad)
    t = t.replace(bad, good)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "fixes =", n)
