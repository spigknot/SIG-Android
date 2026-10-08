#!/bin/bash
# r9-aba2: re-run do ABA real (com o fix do fprintf) + pulls exec-out (RAW)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r9.log"
OUT="C:/llama-npu/rodada2/logs/r9-aba2.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R9 ABA2 ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
# re-push dos prompts + cap (garantia)
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-0.txt /data/local/tmp/p4-prompt-0.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-1.txt /data/local/tmp/p4-prompt-1.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-2.txt /data/local/tmp/p4-prompt-2.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-cap-952.txt /data/local/tmp/p4-cap.txt >/dev/null 2>&1

roda() {  # $1=rotulo; $2=rota; $3=sleep; $4=json
  echo "=== [$1] ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt; printf '$2\\n$2\\n$2\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "PROMPT\\[[0-9]+\\]|DIV_DET|SESS\\[[0-9]+\\] .* valid:|RESULTADO|texto_len" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 14 | tee -a "$OUT"
  "$A" -s "$S" exec-out run-as $PKG cat files/r6-sess.jsonl > "$4" 2>/dev/null
  echo "    JSON: $(wc -c < "$4" 2>/dev/null) bytes" | tee -a "$OUT"
}

roda "ABA2-OC" 'opencl' 300 "C:/llama-npu/rodada2/logs/r9-aba2-oc.jsonl"
roda "ABA2-VK" 'vulkan' 300 "C:/llama-npu/rodada2/logs/r9-aba2-vk.jsonl"

echo "R9_ABA2_DONE" | tee -a "$OUT"
