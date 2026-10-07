#!/bin/bash
# c3-translate.sh — Bloco C.3: traducoes reais EN->PT com os textos do corpus (CPU e HTP0)
# Mede tempo/velocidade de traducao por backend; salva saidas e timings.
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
M="models/Hy-MT2-1.8B-Q4_0.gguf"

run() {
  echo "== $1 =="
  python runner2.py --name "$1" --timeout "$2" --need-model --tag "C.3 traducao" \
    --shell "$3" 2>&1 | tail -n 6
}

for T in short-409 medium-952 long-2335 xlong-4924; do
  for BE in "CPU:-ngl 0" "HTP0:-dev HTP0 -ngl 99"; do
    NAME="${BE%%:*}"; FLAGS="${BE##*:}"
    run "c3_${T}_${NAME}" 1200 "./bin/llama-cli -m $M $FLAGS --temp 0 -s 42 --simple-io -st -n 2048 -f corpus/prompt-${T}.txt > c3-out.log 2>&1; grep -E 'Prompt:|error' c3-out.log | tail -n 2; tail -n 3 c3-out.log | head -n 2"
  done
done

echo "== corpus restante (longo) fica para depois se tempo curto =="
echo C3_DONE
