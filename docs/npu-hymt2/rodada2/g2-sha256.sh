#!/bin/bash
# g2-sha256.sh — recaptura do teste de consistencia com SHA-256 (substitui o MD5 do G1)
set -u
cd D:/Projetos/SIG/docs/npu-hymt2/rodada2
python runner2.py --name g2_sha256 --timeout 900 --need-model --serial 100.108.27.64:41257 --tag "G2 sha256" --shell "
P='Translate to Portuguese: The officer arrived at the scene and interviewed the witnesses about the traffic accident.'
for BE in 'HTP0:-dev HTP0 -ngl 99' 'CPU:-ngl 0'; do
  NAME=\${BE%%:*}; FLAGS=\${BE##*:}
  ./bin/llama-cli -m models/Hy-MT2-1.8B-Q4_0.gguf \$FLAGS -st -n 48 -s 42 --temp 0 --simple-io -p \"\$P\" > g2-\$NAME.log 2>&1
  echo "\$NAME rc=\$?"
  sed -n '/^> /,/^$/p' g2-\$NAME.log | tail -n +2 | head -n 1 > g2-\$NAME.txt
  sha256sum g2-\$NAME.txt
  md5sum g2-\$NAME.txt
done"
echo G2_DONE
