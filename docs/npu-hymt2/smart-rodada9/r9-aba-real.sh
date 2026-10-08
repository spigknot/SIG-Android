#!/bin/bash
# r9-aba-real: A952 -> Bcurto -> A952 NO MESMO PAR (sessao unica, prompts por request)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r9.log"
OUT="C:/llama-npu/rodada2/logs/r9-aba.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R9 ABA REAL ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

roda() {  # $1=rotulo; $2=rota; $3=sleep; $4=json
  echo "=== [$1] ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt; printf '$2\\n$2\\n$2\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "PROMPT\\[[0-9]+\\]|SESS\\[[0-9]+\\] .* valid:|DIV_DET|RESULTADO|texto_len" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 16 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs" | tee -a "$OUT"
}

roda "ABA-real-OC" 'opencl' 300 "C:/llama-npu/rodada2/logs/r9-aba-oc.jsonl"
roda "ABA-real-VK" 'vulkan' 300 "C:/llama-npu/rodada2/logs/r9-aba-vk.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-prompt-[0-9].txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-prompt.txt /data/local/tmp/p4-cap.txt" >/dev/null 2>&1
echo "R9_ABA_DONE" | tee -a "$OUT"
