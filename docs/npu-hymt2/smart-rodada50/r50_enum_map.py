#!/usr/bin/env python3
# r50_enum_map.py — o MAPA nome->inteiro COMPILADO do cohort REAL (matmul-ops.h!)
import re, subprocess, sys
SRC = "/root/sigcand2/ggml/src/ggml-hexagon/htp/matmul-ops.h"
src = open(SRC, encoding="utf-8", errors="replace").read()
m = re.search(r"enum htp_mm_kernel_type \{.*?\n\};", src, re.S)
if not m:
    print("ENUM_NAO_ACHADO"); sys.exit(1)
enum = m.group(0)
names = []
for n in re.findall(r"(HTP_MM_KERNEL_[A-Z0-9_]+)", enum):
    if n not in names: names.append(n)
print("identificadores extraidos:", len(names))
code = "#include <cstdio>\n" + enum + "\nint main(){\n"
for i, n in enumerate(names):
    code += f'  printf("MAPA_COHORT %s=%d\\n", "{n}", (int) {n});\n'
code += "  return 0;}\n"
open("/root/r50_enum_map.cpp", "w").write(code)
r = subprocess.run(["g++", "-std=c++17", "-O2", "/root/r50_enum_map.cpp", "-o", "/root/r50_enum_map"], capture_output=True, text=True)
print("BUILD_RC =", r.returncode)
if r.returncode != 0:
    print(r.stderr[-1500:]); sys.exit(1)
rr = subprocess.run(["/root/r50_enum_map"], capture_output=True, text=True)
print(rr.stdout)
print("RUN_EXIT =", rr.returncode)
