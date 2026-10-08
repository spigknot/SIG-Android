#!/usr/bin/env python3
# R14: contexto 4230-4260 kq2 + funcoes dos hunks 16/17
import re
t = open("/root/kq2/ggml/src/ggml-hexagon/ggml-hexagon.cpp").read().splitlines()
print("=== kq2 4225-4265 (o case Q6_K em 4243) ===")
for i in range(4224, 4265): print(f"{i+1:5}|{t[i][:132]}")
print()
K = open("/root/kq-target/ggml-hexagon.cpp").read().splitlines()
# achar as funcoes dos hunks 16/17 no KQT: os ctx 'return kparams->kernel_type == HTP_MM_KERNEL_HMX_2D;'
print("=== KQT: ocorrencias 'kernel_type == HTP_MM_KERNEL_HMX_2D' ===")
for i, ln in enumerate(K):
    if "kernel_type == HTP_MM_KERNEL_HMX_2D" in ln:
        # inicio da funcao (subir ate 'static ')
        j = i
        while j > 0 and not K[j].startswith("static "): j -= 1
        print(f"{i+1:5}| func: {K[j].strip()[:110]}")
print()
print("=== KQT: ocorrencias 'is_hmx_weight_type(src0->type)' ===")
for i, ln in enumerate(K):
    if "is_hmx_weight_type(src0->type)" in ln:
        j = i
        while j > 0 and not K[j].startswith("static "): j -= 1
        print(f"{i+1:5}| func: {K[j].strip()[:110]}")
print()
print("=== kq2: 'HTP_MM_KERNEL_HMX_2D' ===")
for i, ln in enumerate(t):
    if "HTP_MM_KERNEL_HMX_2D" in ln:
        j = i
        while j > 0 and not t[j].startswith("static "): j -= 1
        print(f"{i+1:5}| func: {t[j].strip()[:110]} | {ln.strip()[:90]}")
print()
print("=== kq2: 2880-2900 (guard 2) ===")
for i in range(2879, 2900): print(f"{i+1:5}|{t[i][:132]}")
