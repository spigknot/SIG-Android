#!/usr/bin/env python3
# R14: verdades cruzadas - SIG original vs KQT nos pontos dos repacks
import re
S = open("/root/sig-smart/llama/ggml/src/ggml-hexagon/ggml-hexagon.cpp").read().splitlines()
K = open("/root/kq-target/ggml-hexagon.cpp").read().splitlines()
def g(lines, tok, nome, n=14):
    print(f"### {nome}: '{tok}'")
    c = 0
    for i, ln in enumerate(lines):
        if tok in ln:
            print(f"{i+1:5}|{ln.strip()[:132]}")
            c += 1
            if c >= n: break
    if c == 0: print("   (nenhum)")
    print()
g(S, "Q4_K", "SIG-orig")
g(S, "repack_tiled_q4", "SIG-orig")
g(K, "case GGML_TYPE_Q4_K", "KQT")
g(K, "case GGML_TYPE_Q6_K", "KQT")
g(K, "repack_q4_K_tiled", "KQT", 6)
g(K, "repack_tiled_q4_K", "KQT", 6)
g(K, "repack_tiled_q6_K", "KQT", 6)
g(K, "repack_q6_K_tiled", "KQT", 6)
