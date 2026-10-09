#!/usr/bin/env python3
# r29_muts3.py: fix do remove (splitlines! sem escapes!)
NL = chr(10)
base = "/root/r29_t"

def remove_case(t, tag):
    lines = t.splitlines()
    out = []; i = 0; removed = 0
    repack = "repack_q%s_K_tiled" % ("4" if tag == "Q4_K" else "6")
    while i < len(lines):
        if lines[i].strip() == ("case GGML_TYPE_%s:" % tag):
            while out and (out[-1].strip().startswith("//") or out[-1].strip() == ""):
                out.pop()
            seen_repack = False
            while i < len(lines):
                if repack in lines[i]:
                    seen_repack = True
                if seen_repack and lines[i].strip() == "break;":
                    break
                i += 1
            i += 1
            if i < len(lines) and lines[i].strip() == "":
                i += 1
            removed += 1
            continue
        out.append(lines[i]); i += 1
    assert removed == 1, "remove %s: %d" % (tag, removed)
    return NL.join(out)

for d, tags in [("m1", ["Q4_K", "Q6_K"]), ("m2", ["Q6_K"])]:
    p = base + "/" + d + "/r29_settensor_adap.inc"
    orig = open(base + "/green/r29_settensor_adap.inc").read()
    nt = orig
    for tag in tags:
        nt = remove_case(nt, tag)
    open(p, "w").write(nt)
    print(d, "ok!")
