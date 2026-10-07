#!/bin/bash
# r7-abtune2: T4 NHVX=8 (threads reais ao iface) vs T5 padrao — o teste da chamada
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r7.log"
OUT="C:/llama-npu/rodada2/logs/r7-abtune2.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R7 A/B tune2 (NHVX) ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

sessao() {  # $1=nome; $2=tune; $3=sleep; $4=json
  echo "=== [$1] tune='$2' ===" | tee -a "$OUT"
  if [ -z "$2" ]; then "$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1;
  else "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-tune.txt" >/dev/null 2>&1; fi
  "$A" -s "$S" shell "printf 'puro:htp\\npuro:htp\\npuro:htp\\npuro:htp\\n' > /data/local/tmp/p4-session.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity >/dev/null 2>&1; input keyevent KEYCODE_WAKEUP" >/dev/null 2>&1
  sleep "$3"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "TUNE:\\|SESS\\[.*htp.*prefill" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 10 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs" | tee -a "$OUT"
}

sessao "T4-nhvx8"  'GGML_HEXAGON_NHVX=8\n' 65 "C:/llama-npu/rodada2/logs/t4-nhvx8.jsonl"
sessao "T5-nhvx4"  'GGML_HEXAGON_NHVX=4\n' 65 "C:/llama-npu/rodada2/logs/t5-nhvx4.jsonl"
sessao "T6-nhvx8-combo" 'GGML_HEXAGON_NHVX=8\nGGML_HEXAGON_OPBATCH=1280\nGGML_HEXAGON_OPQUEUE=32\n' 65 "C:/llama-npu/rodada2/logs/t6-combo.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "R7_ABTUNE2_DONE" | tee -a "$OUT"
