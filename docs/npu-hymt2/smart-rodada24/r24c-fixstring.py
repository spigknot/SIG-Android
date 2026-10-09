#!/usr/bin/env python3
# R24c: conserta as strings KQSET quebradas (newline real -> escape \n!)
BSN = chr(92) + "n"  # backslash + n, SEM ambiguidade de escapes!
for path in ["/root/sigcand2/ggml/src/ggml-hexagon/ggml-hexagon.cpp",
             "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"]:
    t = open(path, "rb").read().replace(b"\r\n", b"\n").decode()
    lines = t.split("\n")
    out = []
    i = 0
    joins = 0
    while i < len(lines):
        ln = lines[i]
        nxt = lines[i+1] if i + 1 < len(lines) else ""
        ends_broken = (ln.rstrip().endswith("(upload parcial!)") or
                       ln.rstrip().endswith("repack_q4_K_tiled") or
                       ln.rstrip().endswith("repack_q6_K_tiled"))
        if ends_broken and nxt.lstrip().startswith(chr(34)):
            out.append(ln.rstrip() + BSN + nxt.lstrip())
            joins += 1
            i += 2
            continue
        out.append(ln)
        i += 1
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print(path, "joins =", joins)
