#!/bin/bash
# r7-ab-tune: A/B do hexagon SO por env (same APK): tune MM_SELECT=2 vs padrao
# intercalado: T1(2) T2(padrao) T3(2). HTP solo x4 por sessao.
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r7.log"
OUT="C:/llama-npu/rodada2/logs/r7-abtune.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R7 A/B tune ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

sessao() {  # $1=nome; $2=conteudo tunefile (vazio=rm); $3=sleep; $4=json-local
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
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -a "TUNE:\\|SESS\\[.*htp.*prefill" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 12 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs -> $4" | tee -a "$OUT"
}

sessao "T1-mmselect2" 'GGML_HEXAGON_MM_SELECT=2\n' 65 "C:/llama-npu/rodada2/logs/t1-mm2.jsonl"
sessao "T2-padrao" "" 65 "C:/llama-npu/rodada2/logs/t2-padrao.jsonl"
sessao "T3-mmselect2-r2" 'GGML_HEXAGON_MM_SELECT=2\n' 65 "C:/llama-npu/rodada2/logs/t3-mm2.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "R7_ABTUNE_DONE" | tee -a "$OUT"
