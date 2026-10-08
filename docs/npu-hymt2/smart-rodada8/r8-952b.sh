#!/bin/bash
# r8-952b: SMARTS no 952 (validacao ABA) — sem input keyevent (o keep-alive cobre)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r8.log"
OUT="C:/llama-npu/rodada2/logs/r8-952.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R8-952b Smarts ($(date -u +%H:%M:%SZ)) ===" | tee -a "$OUT"
"$A" -s "$S" shell "dumpsys power | grep -m1 mWakefulness" | tr -d '\r' | tee -a "$OUT"

roda() {  # $1=rotulo; $2=rotas; $3=sleep; $4=json
  echo "=== [$1] ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt; printf '$2' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "SESS\\[[0-9]+\\] .* (valid|puro):|texto_len|RESULTADO|SESSAO.*fim" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 12 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs -> $4" | tee -a "$OUT"
}

roda "952-SmartOC"  'opencl\n' 500 "C:/llama-npu/rodada2/logs/r8-952-oc.jsonl"
roda "952-SmartVK"  'vulkan\n' 420 "C:/llama-npu/rodada2/logs/r8-952-vk.jsonl"

echo "R8_952B_DONE" | tee -a "$OUT"
