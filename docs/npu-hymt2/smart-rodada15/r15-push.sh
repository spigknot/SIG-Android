#!/bin/bash
# r15-push: envia o modelo ao device + verifica sha + re-roda o gate
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
OUT="C:/llama-npu/rodada2/logs/r15-push.txt"

echo "=== R15 PUSH ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
echo "--- sha local ---" | tee -a "$OUT"
sha256sum C:/llama-npu/rodada2/hymt2_app.gguf | cut -c1-64 | tee -a "$OUT"
echo "--- push (~1.1GB) ---" | tee -a "$OUT"
"$A" -s "$S" push C:/llama-npu/rodada2/hymt2_app.gguf /data/local/tmp/hymt2_app.gguf 2>&1 | tail -n 2 | tee -a "$OUT"
echo "--- sha no device (exec-out p/ nao corromper!) ---" | tee -a "$OUT"
"$A" -s "$S" exec-out "sha256sum /data/local/tmp/hymt2_app.gguf 2>/dev/null || toybox sha256sum /data/local/tmp/hymt2_app.gguf" 2>&1 | tr -d '\r' | cut -c1-64 | tee -a "$OUT"
echo "--- teste rapido (puro:opencl, 1 req) ---" | tee -a "$OUT"
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-envelope.txt; printf 'puro:opencl\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop br.gov.sp.pcsp.npuprobe" >/dev/null 2>&1
sleep 2
L="C:/llama-npu/rodada2/logs/npuprobe-r15.log"
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n br.gov.sp.pcsp.npuprobe/.MainActivity" >/dev/null 2>&1
sleep 90
tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "puro:opencl" | sed 's/.*NpuProbe([0-9 ]*): /  /' | head -n 4 | tee -a "$OUT"
echo "R15_PUSH_DONE" | tee -a "$OUT"
