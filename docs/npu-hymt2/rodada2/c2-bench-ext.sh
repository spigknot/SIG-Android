#!/bin/bash
# c2-bench-ext.sh — Bloco C.2: bench estendido nos 3 backends (background)
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "C.2 bench ext" \
    --shell "$3" 2>&1 | tail -n 5
}

# CPU (baseline honesto): sem -dev, ngl 0
run c2_cpu_ext 2400 "./bin/llama-bench -m models/Hy-MT2-1.8B-Q4_0.gguf -ngl 0 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\||^$' | tail -n 12"

# HTP0
run c2_htp_ext 2400 "./bin/llama-bench -m models/Hy-MT2-1.8B-Q4_0.gguf -dev HTP0 -ngl 99 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\||^$' | tail -n 12"

# GPUOpenCL
run c2_ocl_ext 2400 "./bin/llama-bench -m models/Hy-MT2-1.8B-Q4_0.gguf -dev GPUOpenCL -ngl 99 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\||^$' | tail -n 12"

echo C2_DONE
