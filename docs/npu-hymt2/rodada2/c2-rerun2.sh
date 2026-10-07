#!/bin/bash
# c2-rerun2.sh — re-bench CPU/OCL em condicao ACORDADA (stayon usb ativo; sem waker-loop)
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
M="models/Hy-MT2-1.8B-Q4_0.gguf"

bench() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "C.2 v3 stayon" \
    --shell "$3" 2>&1 | tail -n 6
}

bench c2_cpu_awake2 2400 "./bin/llama-bench -m $M -ngl 0 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\\| h' | tail -n 10"
bench c2_ocl_awake2 2400 "./bin/llama-bench -m $M -dev GPUOpenCL -ngl 99 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\\| h' | tail -n 10"

echo C2_RERUN2_DONE
