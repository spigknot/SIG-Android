#!/usr/bin/env python3
# R24e: conserta BSN fora das aspas:  )"\n,  =>  )\n",
BSN = chr(92) + "n"
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    bad = ')"' + BSN + ','
    good = ')' + BSN + '",'
    n = t.count(bad)
    t = t.replace(bad, good)
    open(path, "w", encoding="utf-8", newline="\n").write(t)
    print(path, "fixes =", n)
# verificar as linhas!
t = open("/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp").read().split("\n")
for i, ln in enumerate(t[1365:1380], start=1366):
    print(i, "|", ln[:130])
