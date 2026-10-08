#!/bin/bash
# r10-aba-vk: fechar o A-B-A Vulkan com 3 outputs (aguardo REAL + completion marker)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r10.log"
OUT="C:/llama-npu/rodada2/logs/r10-aba-vk.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R10 ABA-VK ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-0.txt /data/local/tmp/p4-prompt-0.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-1.txt /data/local/tmp/p4-prompt-1.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-2.txt /data/local/tmp/p4-prompt-2.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-cap-952.txt /data/local/tmp/p4-cap.txt >/dev/null 2>&1

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt; printf 'vulkan\\nvulkan\\nvulkan\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1

# aguardo REAL: 3x "SESSAO.*: fim"? nao: 1 sessao -> 1 fim APOS os 3 requests
# criterio: linha 'SESSAO(....): fim (3 requests)' no log (completion marker!)
for t in $(seq 1 60); do
  sleep 10
  OK=$(tail -n +$((L0+1)) "$L" 2>/dev/null | grep -ac "fim (3 requests)")
  [ "$OK" -ge 1 ] && break
done
echo "[R10-ABA-VK] completion marker em ~$((t*10))s: $OK" | tee -a "$OUT"
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "PROMPT\\[[0-9]+\\]|DIV_DET|SESS\\[[0-9]+\\] .*valid:|RESULTADO" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 20 | tee -a "$OUT"
sleep 3
"$A" -s "$S" exec-out run-as $PKG cat files/r6-sess.jsonl > C:/llama-npu/rodada2/logs/r10-aba-vk.jsonl 2>/dev/null
echo "    JSON: $(wc -c < C:/llama-npu/rodada2/logs/r10-aba-vk.jsonl 2>/dev/null) bytes" | tee -a "$OUT"
echo "R10_ABA_VK_DONE" | tee -a "$OUT"
