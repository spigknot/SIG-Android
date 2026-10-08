#!/bin/bash
# r15-gate: RED/GREEN do NUMGATE (device vivo!) — 5 casos com dst2
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r15.log"
OUT="C:/llama-npu/rodada2/logs/r15-gate.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R15 GATE RED/GREEN ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-prompt.txt /data/local/tmp/p4-cap.txt /data/local/tmp/p4-envelope.txt /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1

roda() {  # $1=rotulo $2=rota $3=mutante(${vazio}=none) $4=envelope(${vazio}=ausente) $5=sleep
  echo "=== [$1] rota=$2 mut=${3:-none} env=${4:-ausente} ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-envelope.txt" >/dev/null 2>&1
  [ -n "$3" ] && "$A" -s "$S" shell "echo $3 > /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  if [ -n "$4" ]; then
    "$A" -s "$S" shell "printf 'env_imp=%s\ncalibration_id=%s\n' '$4' 'R15-TEST' > /data/local/tmp/p4-envelope.txt" >/dev/null 2>&1
  fi
  "$A" -s "$S" shell "printf '%s\n' '$2' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1"
  sleep "$5"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "NUMGATE|NUMRES|NUM:" | sed 's/.*NpuProbe([0-9 ]*): /  /' | head -n 8 | tee -a "$OUT"
  echo "" | tee -a "$OUT"
}

# G1 GREEN-normal: sem envelope => CALIBRACAO_PENDENTE (nunca PASS!)
roda "G1-normal" "numeric:htp:opencl:cpu:opencl" "" "" 170
# G2 RED-import: envelope TEST 2.5 + +50 => FAIL(import>env)
roda "G2-perturba" "numeric:htp:opencl:cpu:opencl" "smart_perturba" "2.5" 170
# G3 RED-reference: dst2 invalido => FAIL(reference)
roda "G3-refbogus" "numeric:htp:opencl:cpu:bogus" "" "2.5" 120
# G4 invariancia: offset global => metricas iguais (prob.)
roda "G4-offset" "numeric:htp:opencl:cpu:opencl" "smart_offset" "2.5" 170
# G5 sem dst2: SEM-REFERENCIA (a lacuna R13 documentada no payload!)
roda "G5-nodst2" "numeric:htp:opencl" "" "" 120

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-envelope.txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
echo "R15_GATE_DONE" | tee -a "$OUT"
