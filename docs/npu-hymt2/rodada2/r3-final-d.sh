#!/bin/bash
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
S="3B15BD00FVE00000"
M="models/Hy-MT2-1.8B-Q4_0.gguf"

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --tag "D final v2" --serial $S --shell "$3" 2>&1 | tail -n 5
}

for NP in 512 1024; do
  run "d_v5g_htp_np$NP" 900 "./bin/hybrid_bridge_v5 -m $M --mode htp --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 1 | cut -c1-320"
  run "d_v5g_cpu_np$NP" 1500 "./bin/hybrid_bridge_v5 -m $M --mode cpu --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 1 | cut -c1-320"
  run "d_v5g_hybrid_np$NP" 1500 "./bin/hybrid_bridge_v5 -m $M --mode hybrid --np $NP --ngen 64 2>/dev/null | grep RESULT_JSON | head -n 1 | cut -c1-320"
done

echo D_DONE
