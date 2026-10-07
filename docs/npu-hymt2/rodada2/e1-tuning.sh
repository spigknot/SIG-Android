#!/bin/bash
# e1-tuning.sh — Bloco E: tuning bounded (CPU threads, HTP configs)
# Roda em serie via runner2. Curto: pp256/tg64 quando so comparativo.
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "E tuning" \
    --shell "$3" 2>&1 | tail -n 5
}
M="models/Hy-MT2-1.8B-Q4_0.gguf"

# --- E.2 CPU threads (1/2/4/6/8) baseline curto ---
run e2_cpu_threads 1800 "./bin/llama-bench -m $M -ngl 0 -t 1,2,4,6,8 -p 128 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 12"

# --- E.3 HTP: OPBATCH {128, 640, 1280} (1 param por vez) ---
run e3_htp_opbatch128 900 "GGML_HEXAGON_OPBATCH=128 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"
run e3_htp_opbatch640 900 "GGML_HEXAGON_OPBATCH=640 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"

# --- E.3 HTP: MM_SELECT {1=HVX, 0=disable, default nao setado} ---
run e3_htp_mm1_hvx 900 "GGML_HEXAGON_MM_SELECT=1 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"
run e3_htp_mm2_hmx 900 "GGML_HEXAGON_MM_SELECT=2 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"

# --- E.3 HTP: NHVX {4, 2} ---
run e3_htp_nhvx4 900 "GGML_HEXAGON_NHVX=4 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"
run e3_htp_nhvx2 900 "GGML_HEXAGON_NHVX=2 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"

# --- E.3 HTP: OPPOLL=0 (sem polling) ---
run e3_htp_oppoll0 900 "GGML_HEXAGON_OPPOLL=0 ./bin/llama-bench -m $M -dev HTP0 -ngl 99 -p 256 -n 64 -r 2 2>&1 | grep -E '^\| h' | tail -n 4"

echo E1_DONE
