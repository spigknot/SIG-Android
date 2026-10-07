#!/bin/bash
# r7-focais: testes FOCAIS NA SESSAO (ordem R7-2): cap1/cap2/eog_tok1/import_falha/
# source_extra(bench origem)/alias-role (mesmo backend src=dst -> 2 ctxs!).
# Cada mutante: 1 sessao com 2 requests (o gate deve segurar; o 2o confirma estado).
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r7.log"
OUT="C:/llama-npu/rodada2/logs/r7-focais.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R7 FOCAIS NA SESSAO ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

roda() {  # $1=rotulo; $2=rotas; $3=mutante; $4=sleep
  if [ -z "$3" ]; then "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1;
  else "$A" -s "$S" shell "echo $3 > /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1; fi
  "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1; input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
  sleep "$4"
  echo "--- [$1] mut=${3:-none}" | tee -a "$OUT"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "RESULTADO|SESS\\[[0-9]+\\] .* (valid|bench|puro):" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 10 | tee -a "$OUT"
}

roda "F1-sess-cap1"    'opencl@bench\nopencl@bench\n' 'smart_cap1' 55
roda "F2-sess-cap2"    'opencl@bench\nopencl@bench\n' 'smart_cap2' 55
roda "F3-sess-eogtok1" 'opencl@bench\nopencl@bench\n' 'smart_eog_tok1' 55
roda "F4-sess-import"  'opencl@bench\nopencl@bench\n' 'smart_import_falha' 55
roda "F5-sess-srcx"    'opencl@bench\nopencl@bench\n' 'smart_bench_origem_extra' 55
roda "F6-sess-alias"   'vulkan:vulkan@bench\nvulkan:vulkan@bench\n' '' 70
roda "F7-sess-cut"     'opencl@bench\n' '' 55

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-session.txt /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
echo "R7_FOCAIS_DONE" | tee -a "$OUT"
