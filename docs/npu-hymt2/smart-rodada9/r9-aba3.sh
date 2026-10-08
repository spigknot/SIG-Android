#!/bin/bash
# r9-aba3: o ciclo A952->B->A952 COMPLETO (OC), pull apos o fim real da sessao
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r9.log"
OUT="C:/llama-npu/rodada2/logs/r9-aba3.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R9 ABA3 ciclo completo ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-0.txt /data/local/tmp/p4-prompt-0.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-1.txt /data/local/tmp/p4-prompt-1.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-prompt-2.txt /data/local/tmp/p4-prompt-2.txt >/dev/null 2>&1
"$A" -s "$S" push C:/llama-npu/rodada2/p4-cap-952.txt /data/local/tmp/p4-cap.txt >/dev/null 2>&1

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt; printf 'opencl\\nopencl\\nopencl\\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1

# espera o FIM REAL da sessao no log (SESSAO.*fim) com teto de 540s
for t in $(seq 1 54); do
  sleep 10
  FIN=$(tail -n +$((L0+1)) "$L" 2>/dev/null | grep -ac "SESSAO.*: fim")
  [ "$FIN" -ge 1 ] && break
done
echo "[ABA3] fim detectado (ou teto) em ~$((t*10))s" | tee -a "$OUT"
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "PROMPT\\[[0-9]+\\]|DIV_DET|SESS\\[[0-9]+\\] .* valid:|RESULTADO|texto_len" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 30 | tee -a "$OUT"
"$A" -s "$S" exec-out run-as $PKG cat files/r6-sess.jsonl > C:/llama-npu/rodada2/logs/r9-aba3-oc.jsonl 2>/dev/null
echo "    JSON: $(wc -c < C:/llama-npu/rodada2/logs/r9-aba3-oc.jsonl 2>/dev/null) bytes" | tee -a "$OUT"
echo "R9_ABA3_DONE" | tee -a "$OUT"
