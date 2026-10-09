#!/usr/bin/env python3
# r30_fused_extrai.py: extrai is_mergeable_mul_mat (VERBATIM!) + a expressao
# do src1_row_size (fused!) do fonte real!
import hashlib, re

S = "/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp"
t = open(S, "rb").read().replace(b"\r\n", b"\n").decode()
lines = t.split("\n")

# (a) is_mergeable_mul_mat (VERBATIM!)
for i, ln in enumerate(lines):
    if "static bool is_mergeable_mul_mat(const ggml_tensor * t) {" in ln:
        depth = 0; started = False; out = []
        for j in range(i, len(lines)):
            out.append(lines[j])
            for ch in lines[j]:
                if ch == "{": depth += 1; started = True
                elif ch == "}": depth -= 1
            if started and depth == 0: break
        body = "\n".join(out)
        print("# is_mergeable_mul_mat: L%d..%d sha=%s" % (i+1, j+1, hashlib.sha256(body.encode()).hexdigest()[:16]))
        open("/root/r30_merge_gen.inc", "w").write(
            "// GERADO (r30_fused_extrai.py!) — VERBATIM de %s L%d..%d\n" % (S, i+1, j+1) + body + "\n")
        break
else:
    raise SystemExit("is_mergeable_mul_mat NAO ACHADO!")

# (b) a linha do src1_row_size no precompute_fused_ffn_params!
found = 0
for i, ln in enumerate(lines):
    if "const size_t src1_row_size" in ln and "fused" not in ln.lower():
        # dentro do precompute_fused?! (proximo do L3125/260!)
        if i > 3100 and i < 3180:
            print("# src1_row_size (fused!): L%d: %s" % (i+1, ln.strip()[:110]))
            open("/root/r30_rowsize_gen.inc", "w").write(
                "// GERADO — a expressao REAL do fused (L%d!):\n" % (i+1) + ln.strip() + "\n")
            found += 1
if not found:
    # tentar de novo (qualquer linha com Q4_1-only!)
    for i, ln in enumerate(lines):
        if "src1_row_size" in ln and "(wtype == GGML_TYPE_Q4_1)" in ln:
            print("# src1_row_size (sem Q4_K!): L%d: %s" % (i+1, ln.strip()[:110]))
            open("/root/r30_rowsize_gen.inc", "w").write(
                "// GERADO — a expressao do fused (L%d!):\n" % (i+1) + ln.strip() + "\n")
            found += 1
print("# ok: %d linhas de row_size" % found)
