#!/bin/bash
# r15-gate2: re-run dos 3 RED/GREEN com o probe v2 (mutantes pos 4 + fix reference)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r15.log"
OUT="C:/llama-npu/rodada2/logs/r15-gate2.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R15 GATE2 RED ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

roda() {
  echo "=== [$1] rota=$2 mut=${3:-none} env=${4:-ausente} ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-envelope.txt" >/dev/null 2>&1
  [ -n "$3" ] && "$A" -s "$S" shell "echo $3 > /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  [ -n "$4" ] && "$A" -s "$S" shell "printf 'env_imp=%s\ncalibration_id=%s\n' '$4' 'R15-TEST' > /data/local/tmp/p4-envelope.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "printf '%s\n' '$2' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep 150
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "NUMGATE" | sed 's/.*NpuProbe([0-9 ]*): /  /' | head -n 3 | tee -a "$OUT"
  echo "" | tee -a "$OUT"
}

roda "R2-perturba" "numeric:htp:opencl:cpu:opencl" "smart_perturba" "2.5"
roda "R3-refbogus" "numeric:htp:opencl:cpu:bogus" "" "2.5"
roda "R4-offset" "numeric:htp:opencl:cpu:opencl" "smart_offset" "2.5"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-envelope.txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
echo "R15_GATE2_DONE" | tee -a "$OUT"
