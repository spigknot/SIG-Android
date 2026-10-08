#!/bin/bash
# r11-sweep2: re-varredura COM keep-alive (condicao comparavel): padrao + 3 candidatos
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r11.log"
OUT="C:/llama-npu/rodada2/logs/r11-sweep2.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R11 SWEEP2 ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" shell "dumpsys power | grep -m1 mWakefulness" | tr -d '\r' | tee -a "$OUT"
roda() {  # $1=rotulo; $2=tune; $3=sleep
  if [ -z "$2" ]; then "$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1;
  else "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-tune.txt" >/dev/null 2>&1; fi
  "$A" -s "$S" shell "printf 'puro:htp\\npuro:htp\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1" 
  sleep "$3"
  R=$(tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "puro:htp puro: ok" | sed -E 's/.*(prefill=[0-9.]+ms gen=[0-9.]+ms).*/\1/' | tr '\\n' '|')
  echo "[$1] $R" | tee -a "$OUT"
}

roda "T0-padrao"    "" 55
roda "T1-hostbuf0"  'GGML_HEXAGON_HOSTBUF=0\n' 55
roda "T2-opstage2"  'GGML_HEXAGON_OPSTAGE=2\n' 55
roda "T3-padrao-b"  "" 55
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "R11_SWEEP2_DONE" | tee -a "$OUT"
