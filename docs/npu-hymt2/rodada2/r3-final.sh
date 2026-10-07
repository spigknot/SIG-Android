#!/bin/bash
# r3-final.sh — testes finais R3 via USB (runner2; serial USB estavel)
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
S="3B15BD00FVE00000"
APPM="/sdcard/Android/data/br.gov.sp.pcsp.launcher/files/hymt2_models/Hy-MT2-1.8B-q4_k_m.gguf"
M="models/Hy-MT2-1.8B-Q4_0.gguf"

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --tag "${4:-R3-final}" --serial $S --shell "$3" 2>&1 | tail -n 6
}

# C1: Q4_K_M e2e CPU (redos; stayon usb ativo)
run c1f_short_CPU 900 "./bin/llama-cli -m $APPM -ngl 0 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-short-409.txt > c1fs.log 2>&1; echo rc=\$?; grep -E 'Prompt:' c1fs.log | tail -n 1" "C.1 final"
run c1f_medium_CPU 1200 "./bin/llama-cli -m $APPM -ngl 0 -st -n 2048 -s 42 --temp 0 --simple-io -f corpus/prompt-medium-952.txt > c1fm.log 2>&1; echo rc=\$?; grep -E 'Prompt:' c1fm.log | tail -n 1" "C.1 final"

# D: híbrido v5 np512 e np1024 (harness corrigido)
for NP in 512 1024; do
  run "d_v5f_htp_np$NP" 900 "./bin/hybrid_bridge_v5 -m $M --mode htp --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D final"
  run "d_v5f_cpu_np$NP" 1500 "./bin/hybrid_bridge_v5 -m $M --mode cpu --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D final"
  run "d_v5f_hybrid_np$NP" 1500 "./bin/hybrid_bridge_v5 -m $M --mode hybrid --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 3" "D final"
done

echo R3_FINAL_DONE
