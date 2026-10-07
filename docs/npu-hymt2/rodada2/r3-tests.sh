#!/bin/bash
# r3-tests.sh — RODADA 3: C1 (Q4_K_M e2e) + C3 (numericos Q4_K/Q6_K) + D (hibrido v5)
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
APPM="/sdcard/Android/data/br.gov.sp.pcsp.launcher/files/hymt2_models/Hy-MT2-1.8B-q4_k_m.gguf"
M="models/Hy-MT2-1.8B-Q4_0.gguf"

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --tag "${4:-R3}" --serial 100.108.27.64:41257 --shell "$3" 2>&1 | tail -n 6
}

# --- C3: numericos Q4_K / Q6_K no HTP0 (MUL_MAT) ---
run c3_mulmat_q4k 900 "./bin/test-backend-ops -b HTP0 -o MUL_MAT -p 'type_a=q4_K' test > r3a.log 2>&1; echo RC=\$?; tail -n 3 r3a.log"
run c3_mulmat_q6k 900 "./bin/test-backend-ops -b HTP0 -o MUL_MAT -p 'type_a=q6_K' test > r3b.log 2>&1; echo RC=\$?; tail -n 3 r3b.log"

# --- C1: Q4_K_M produto end-to-end (traducao, corpus R2 pinado) ---
run c1_q4km_short_HTP 900 "./bin/llama-cli -m $APPM -dev HTP0 -ngl 99 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-short-409.txt > r3c.log 2>&1; echo rc=\$?; grep -E 'Prompt:|error' r3c.log | tail -n 2" "C.1 produto e2e"
run c1_q4km_short_CPU 900 "./bin/llama-cli -m $APPM -ngl 0 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-short-409.txt > r3d.log 2>&1; echo rc=\$?; grep -E 'Prompt:|error' r3d.log | tail -n 2" "C.1 produto e2e"
run c1_q4km_medium_HTP 1200 "./bin/llama-cli -m $APPM -dev HTP0 -ngl 99 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-medium-952.txt > r3e.log 2>&1; echo rc=\$?; grep -E 'Prompt:|error' r3e.log | tail -n 2" "C.1 produto e2e"
run c1_q4km_medium_CPU 1200 "./bin/llama-cli -m $APPM -ngl 0 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-medium-952.txt > r3f.log 2>&1; echo rc=\$?; grep -E 'Prompt:|error' r3f.log | tail -n 2" "C.1 produto e2e"

# --- D: hibrido v5 (Q4_0 label variant) Np 128/512 ---
for NP in 128 512; do
  run "d_v5_htp_np$NP" 900 "./bin/hybrid_bridge_v5 -m $M --mode htp --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D v5"
  run "d_v5_cpu_np$NP" 1200 "./bin/hybrid_bridge_v5 -m $M --mode cpu --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D v5"
  run "d_v5_hybrid_np$NP" 1200 "./bin/hybrid_bridge_v5 -m $M --mode hybrid --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D v5"
done

echo R3_TESTS_DONE
