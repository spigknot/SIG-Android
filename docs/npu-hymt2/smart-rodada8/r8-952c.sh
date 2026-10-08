#!/bin/bash
# r8-952c: ABA no MESMO par de contextos (sessao com 3 requests do mesmo prompt)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r8.log"
OUT="C:/llama-npu/rodada2/logs/r8-952.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R8-952c ABA ($(date -u +%H:%M:%SZ)) ===" | tee -a "$OUT"

roda() {  # $1=rotulo; $2=rotas(3x!); $3=sleep; $4=json
  echo "=== [$1] ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt; printf '$2' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "SESS\\[[0-9]+\\] .* (valid):|texto_len|RESULTADO" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 15 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs" | tee -a "$OUT"
}

roda "952-ABA-OC" 'opencl\nopencl\nopencl\n' 350 "C:/llama-npu/rodada2/logs/r8-952-oc-aba.jsonl"
roda "952-ABA-VK" 'vulkan\nvulkan\nvulkan\n' 300 "C:/llama-npu/rodada2/logs/r8-952-vk-aba.jsonl"

echo "R8_952C_DONE" | tee -a "$OUT"
