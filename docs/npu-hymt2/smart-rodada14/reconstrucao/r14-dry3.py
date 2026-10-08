import subprocess, re, os
W = "/root/kq2/ggml/src/ggml-hexagon"
# (1) converter os 3 alvos para LF
for f in ["htp/matmul-ops.c","htp/matmul-ops.h","ggml-hexagon.cpp"]:
    p = os.path.join(W, f)
    t = open(p, "rb").read().replace(b"\r\n", b"\n")
    open(p, "wb").write(t)
    print("LF:", f, "| tem CR?", b"\r" in t)
# (2) dry-run de novo
for name, pg in [("htp/matmul-ops.c","/root/kq-matmul-ops.c.patch"),
                 ("htp/matmul-ops.h","/root/kq-matmul-ops.h.patch"),
                 ("ggml-hexagon.cpp","/root/kq-hexagon.patch")]:
    r = subprocess.run(["patch","-l","-p4","--fuzz=0","--dry-run","-i",pg], cwd=W, capture_output=True, text=True)
    out = r.stdout + r.stderr
    ok = len(re.findall(r"Hunk #\d+ succeeded", out))
    fails = [ln for ln in out.splitlines() if "FAILED" in ln or "ignored" in ln]
    print(f"== {name}: rc={r.returncode} succeeded={ok} failed={len(fails)}")
    for ln in fails[:12]: print("   ", ln[:110])
