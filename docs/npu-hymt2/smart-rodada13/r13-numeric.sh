#!/bin/bash
# r13-numeric: coleta numerica REAL (952) — numeric:htp:opencl + mutante perturbacao
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r13.log"
OUT="C:/llama-npu/rodada2/logs/r13-numeric.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R13 NUMERIC ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-0.txt /data/local/tmp/p4-prompt.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-cap-952.txt /data/local/tmp/p4-cap.txt >/dev/null 2>&1

roda() {  # $1=rotulo; $2=mutante; $3=sleep
  echo "=== [$1] mut=${2:-none} ===" | tee -a "$OUT"
  if [ -z "$2" ]; then "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1;
  else "$A" -s "$S" shell "echo $2 > /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1; fi
  "$A" -s "$S" shell "printf 'numeric:htp:opencl\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1"
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "NUMCHK|NUMRES|NUM texto|NUM:" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 16 | tee -a "$OUT"
}

roda "N1-numeric" "" 200
roda "N2-perturba" "smart_perturba" 200
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt /data/local/tmp/p4-prompt.txt /data/local/tmp/p4-cap.txt" >/dev/null 2>&1
echo "R13_NUMERIC_DONE" | tee -a "$OUT"
