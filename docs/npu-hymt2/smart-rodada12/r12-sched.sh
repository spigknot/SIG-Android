#!/bin/bash
# r12-sched: placement REAL (GGML_SCHED_DEBUG=1) no baseline SIG (c4bf!)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r12.log"
OUT="C:/llama-npu/rodada2/logs/r12-sched.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R12 SCHED PLACEMENT ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"
"$A" -s "$S" shell "printf 'GGML_SCHED_DEBUG=1\n' > /data/local/tmp/p4-tune.txt; printf 'puro:htp\n' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
"$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
sleep 2
L0=$(wc -l < "$L" 2>/dev/null || echo 0)
"$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1"
sleep 50
# o dump: linhas ASSIGNED/printed com o backend por node; contar MUL_MAT por backend
tail -n +$((L0+1)) "$L" 2>/dev/null > /tmp/janela.txt
echo "--- resumo (contagens exclusivas) ---" | tee -a "$OUT"
echo "linhas [ggml] na janela: $(grep -ac '\[ggml\]' /tmp/janela.txt)" | tee -a "$OUT"
echo "MUL_MAT mencionados: $(grep -ac 'MUL_MAT' /tmp/janela.txt)" | tee -a "$OUT"
for be in HTP0 CPU; do
  n=$(grep -a 'MUL_MAT' /tmp/janela.txt | grep -ac "$be")
  echo "MUL_MAT..$be: $n" | tee -a "$OUT"
done
echo "--- amostra do dump (primeiras linhas de assignments) ---" | tee -a "$OUT"
grep -aE "ASSIGNED|assign|split|SET_CAUSE|-> backend|node_backend" /tmp/janela.txt | head -n 20 | cut -c1-160 | tee -a "$OUT"
grep -a "MUL_MAT" /tmp/janela.txt | head -n 12 | cut -c1-170 | tee -a "$OUT"
echo "R12_SCHED_DONE" | tee -a "$OUT"
