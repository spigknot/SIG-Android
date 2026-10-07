#!/bin/bash
# Bateria piloto R5: 4 sessoes WARM (contextos vivos) + sensores + memoria
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r5.log"
OUT="C:/llama-npu/rodada2/logs/bateria-r5.txt"

echo "=== BATERIA PILOTO R5 ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
echo "--- sensores (thermalservice, identificados) ---" | tee -a "$OUT"
"$A" -s "$S" shell "dumpsys thermalservice 2>/dev/null | grep -m8 -E 'Temperature\\{|mStatus|isStatusOverride'" | tr -d '\r' | tee -a "$OUT"
echo "--- thermal zones ---" | tee -a "$OUT"
"$A" -s "$S" shell "for z in 0 1 2 3; do echo -n z\$z=; cat /sys/class/thermal/thermal_zone\$z/temp 2>/dev/null; echo; done" | tr -d '\r' | tee -a "$OUT"

sessao() {  # $1=rotulo; $2=rotas (com \n); $3=sleep
  echo "" | tee -a "$OUT"
  echo "=== [$1] rotas: $2 ===" | tee -a "$OUT"
  MEM0=$("$A" -s "$S" shell "dumpsys meminfo br.gov.sp.pcsp.npuprobe 2>/dev/null | grep -m1 TOTAL" | tr -d '\r')
  TZ0=$("$A" -s "$S" shell "cat /sys/class/thermal/thermal_zone0/temp" | tr -d '\r')
  "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop br.gov.sp.pcsp.npuprobe" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n br.gov.sp.pcsp.npuprobe/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  echo "    mem0: $MEM0 | temp_z0_antes=$TZ0" | tee -a "$OUT"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "SESS\[" | sed 's/.*NpuProbe([0-9 ]*): /    /' | grep -av "^    SESSAO" | head -n 40 | tee -a "$OUT"
  MEM1=$("$A" -s "$S" shell "dumpsys meminfo br.gov.sp.pcsp.npuprobe 2>/dev/null | grep -m1 TOTAL" | tr -d '\r')
  TZ1=$("$A" -s "$S" shell "cat /sys/class/thermal/thermal_zone0/temp" | tr -d '\r')
  echo "    mem1: $MEM1 | temp_z0_depois=$TZ1" | tee -a "$OUT"
}

sessao "S1-puros-cpu-htp"  'puro:cpu\npuro:htp\npuro:cpu\npuro:htp\npuro:cpu\npuro:htp\n' 45
sessao "S2-puros-ocl-vk"   'puro:opencl\npuro:vulkan\npuro:opencl\npuro:vulkan\npuro:opencl\npuro:vulkan\n' 55
sessao "S3-smart-oc-warm"  'opencl@bench\nopencl@bench\nopencl@bench\n' 75
sessao "S4-smart-vk-warm"  'vulkan@bench\nvulkan@bench\nvulkan@bench\n' 60

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-session.txt /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
echo "" | tee -a "$OUT"
echo "BATERIA_R5_DONE" | tee -a "$OUT"
