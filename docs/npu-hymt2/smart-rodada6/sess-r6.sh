#!/bin/bash
# sess-r6 (R6): bateria matched fiel — sessoes por grupo (<=2 modelos), 1 cold + 3 warm
# por modo, sensores 13, pull JSON nativo por sessao. Ordem de blocos balanceada.
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r6.log"
OUT="C:/llama-npu/rodada2/logs/sess-r6.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== SESS-R6 matched ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
sen13() {
  "$A" -s "$S" shell "for z in 0 1 2 3 4 5 6 7 8 9 10 11 12; do echo -n t\\$z=; cat /sys/class/thermal/thermal_zone\$z/temp 2>/dev/null; echo -n ' '; done" | tr -d '\r' | tee -a "$OUT"
  echo "" | tee -a "$OUT"
}

sessao() {  # $1=nome; $2=rotas; $3=sleep; $4=json-local
  echo "" | tee -a "$OUT"
  echo "=== [$1] ===" | tee -a "$OUT"
  echo -n "ANTES: " | tee -a "$OUT"; sen13
  "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "SESS\[" | sed 's/.*NpuProbe([0-9 ]*): /    /' | grep -av "SESSAO" | head -n 44 | tee -a "$OUT"
  echo -n "DEPOIS: " | tee -a "$OUT"; sen13
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) requests -> $4" | tee -a "$OUT"
}

# Grupo 1 (2 modelos): CPU+HTP intercalado — 1 cold + 3 warm cada
sessao "G1-cpu-htp" 'puro:cpu\npuro:htp\npuro:cpu\npuro:htp\npuro:cpu\npuro:htp\npuro:cpu\npuro:htp\n' 75 "C:/llama-npu/rodada2/logs/g1-cpu-htp.jsonl"
# Grupo 2 (2 modelos): OpenCL+Vulkan intercalado
sessao "G2-ocl-vk" 'puro:opencl\npuro:vulkan\npuro:opencl\npuro:vulkan\npuro:opencl\npuro:vulkan\npuro:opencl\npuro:vulkan\n' 60 "C:/llama-npu/rodada2/logs/g2-ocl-vk.jsonl"
# Grupo 3 (2 modelos): Smart OpenCL (htp+ocl) — 1 cold + 3 warm
sessao "G3-smart-oc" 'opencl@bench\nopencl@bench\nopencl@bench\nopencl@bench\n' 80 "C:/llama-npu/rodada2/logs/g3-smart-oc.jsonl"
# Grupo 4 (2 modelos): Smart Vulkan (htp+vk)
sessao "G4-smart-vk" 'vulkan@bench\nvulkan@bench\nvulkan@bench\nvulkan@bench\n' 70 "C:/llama-npu/rodada2/logs/g4-smart-vk.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "SESS_R6_DONE" | tee -a "$OUT"
