#!/bin/bash
# pf-r6 (R6): microcontrole prefill HTP — solo vs residente, device pos-cooldown.
# 3 sessoes x4 requests (1 cold + 3 warm); sensores 13 zonas + thermal status;
# pull do JSON NATIVO por sessao (run-as).
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r6.log"
OUT="C:/llama-npu/rodada2/logs/pf-r6.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== PF-R6 microcontrole prefill ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" shell "dumpsys thermalservice 2>/dev/null | grep -m3 -E 'Thermal Status|mStatus'" | tr -d '\r' | tee -a "$OUT"
sen13() {
  "$A" -s "$S" shell "for z in 0 1 2 3 4 5 6 7 8 9 10 11 12; do echo -n t\\$z=; cat /sys/class/thermal/thermal_zone\\$z/type 2>/dev/null; echo -n :; cat /sys/class/thermal/thermal_zone\$z/temp 2>/dev/null; echo; done" | tr -d '\r' | paste -sd' ' - | tee -a "$OUT"
}

sessao() {  # $1=nome; $2=rotas; $3=sleep; $4=arquivo-json-local
  echo "" | tee -a "$OUT"
  echo "=== [$1] rotas: $2 ===" | tee -a "$OUT"
  echo -n "ANTES: " | tee -a "$OUT"; sen13
  "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "SESS\[" | sed 's/.*NpuProbe([0-9 ]*): /    /' | grep -av "SESSAO" | head -n 40 | tee -a "$OUT"
  echo -n "DEPOIS: " | tee -a "$OUT"; sen13
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) requests -> $4" | tee -a "$OUT"
}

sessao "PF1-htp-solo"      'puro:htp\npuro:htp\npuro:htp\npuro:htp\n' 50 "C:/llama-npu/rodada2/logs/pf1-htp.jsonl"
sessao "PF2-ocl-solo"      'puro:opencl\npuro:opencl\npuro:opencl\npuro:opencl\n' 40 "C:/llama-npu/rodada2/logs/pf2-ocl.jsonl"
sessao "PF3-htp-com-res-ocl" 'htp:opencl@bench\nhtp:opencl@bench\nhtp:opencl@bench\nhtp:opencl@bench\n' 70 "C:/llama-npu/rodada2/logs/pf3-bridge.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "PF_R6_DONE" | tee -a "$OUT"
