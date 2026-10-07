#!/bin/bash
# g1-stability.sh — Bloco G: serie de estabilidade (compacta) — HTP e CPU alternados
# 6 iteracoes x 2 backends; mesma entrada/seed -> saida deve ser IDENTICA (greedy).
# Coleta telemetria (temp/load) antes e depois; series com reset implicito (processo novo por iter).
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2

python runner2.py --name g1_stability --timeout 1800 --need-model --tag "G estabilidade" --shell "
P='Translate to Portuguese: The officer arrived at the scene and interviewed the witnesses about the traffic accident.'
for i in 1 2 3 4 5 6; do
  echo \"--- iter\$i HTP ---\"
  ./bin/llama-cli -m models/Hy-MT2-1.8B-Q4_0.gguf -dev HTP0 -ngl 99 -st -n 48 -s 42 --temp 0 --simple-io -p \"\$P\" > it.log 2>&1
  echo \"rc=\$?\"; sed -n '/^> /,/^$/p' it.log | tail -n +2 | head -n 1 | md5sum | cut -d' ' -f1
  echo \"--- iter\$i CPU ---\"
  ./bin/llama-cli -m models/Hy-MT2-1.8B-Q4_0.gguf -ngl 0 -st -n 48 -s 42 --temp 0 --simple-io -p \"\$P\" > it.log 2>&1
  echo \"rc=\$?\"; sed -n '/^> /,/^$/p' it.log | tail -n +2 | head -n 1 | md5sum | cut -d' ' -f1
  cat /sys/class/power_supply/battery/temp
done
echo G1_DONE"
