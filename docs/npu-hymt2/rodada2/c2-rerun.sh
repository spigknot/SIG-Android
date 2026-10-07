#!/bin/bash
# c2-rerun.sh — re-bench CPU/OCL em condicao ACORDADA (corrige contaminacao do doze)
# Inclui waker: keyevent WAKEUP a cada 20s durante o bench (impede o device de dormir).
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
M="models/Hy-MT2-1.8B-Q4_0.gguf"

bench() { # nome timeout shell
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "C.2 rerun acordado" \
    --shell "(while true; do input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 20; done) & W=\$!; $3; kill \$W 2>/dev/null; echo WAKER_DONE" 2>&1 | tail -n 6
}

# CPU estendido (acordado)
bench c2_cpu_ext_awake 2400 "./bin/llama-bench -m $M -ngl 0 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\\| h' | tail -n 10"

# OCL estendido (acordado)
bench c2_ocl_ext_awake 2400 "./bin/llama-bench -m $M -dev GPUOpenCL -ngl 99 -p 64,256,512,1024,2048,4096 -n 128,256 -r 3 2>&1 | grep -E '^\\| h' | tail -n 10"

echo C2_RERUN_DONE
