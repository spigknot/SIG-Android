#!/bin/bash
# r8-952: PROVA DE CORRETUDE no corpus 952 (ordem R8-5) — duas rotas Smart
# (validacao ABA com espelho!) + CPU ref. Corpus: prompt-952 (1085 chars,
# setas+historico); cap 400; chunked prefill (nt>128). Timeout-bounded.
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r8.log"
OUT="C:/llama-npu/rodada2/logs/r8-952.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R8-952 corretude ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

roda() {  # $1=rotulo; $2=rotas; $3=sleep; $4=json
  echo "=== [$1] ===" | tee -a "$OUT"
  "$A" -s "$S" shell "rm -f /data/local/tmp/p4-mutante.txt /data/local/tmp/p4-tune.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1; input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
  sleep "$3"
  echo "[$1] janela: $(tail -n +$((L0+1)) "$L" | grep -ac "SESS\\[") linhas SESS" | tee -a "$OUT"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "prompt: |TUNE:|SESS\\[[0-9]+\\] .* (valid|puro):|SESS\\[[0-9]+\\] .* texto_len|SESS\\[[0-9]+\\] .*RESULTADO|fronteiras" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 16 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs -> $4" | tee -a "$OUT"
}

# ordem: CPU ref -> Smart OC -> Smart VK (validação ABA com espelho!)
roda "952-CPU-ref"  'puro:cpu\n' 180 "C:/llama-npu/rodada2/logs/r8-952-cpu.jsonl"
roda "952-SmartOC"  'opencl\n' 480 "C:/llama-npu/rodada2/logs/r8-952-oc.jsonl"
roda "952-SmartVK"  'vulkan\n' 420 "C:/llama-npu/rodada2/logs/r8-952-vk.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-prompt.txt /data/local/tmp/p4-cap.txt /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "R8_952_DONE" | tee -a "$OUT"
