#!/bin/bash
# r8-preinit: RED/GREEN do preinit — tune ANTES do registro (runProbe pulado).
# GREEN1: NHVX=8 + VERBOSE=1 (ver o efetivo nos logs!) | GREEN2: MM_SELECT=2 |
# CONTROLE: sem tune (mesma janela). Fresh process por sessao, 4 reqs warm.
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r8.log"
OUT="C:/llama-npu/rodada2/logs/r8-preinit.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R8 PREINIT RED/GREEN ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

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
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "TUNE:|PULADO|SESS\\[.*htp.*prefill" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 14 | tee -a "$OUT"
  "$A" -s "$S" shell "run-as $PKG cat files/r6-sess.jsonl" > "$4" 2>/dev/null
  echo "    JSON: $(wc -l < "$4" 2>/dev/null) reqs" | tee -a "$OUT"
}

sessao "G1-nhvx8-verb"  'GGML_HEXAGON_NHVX=8\nGGML_HEXAGON_VERBOSE=1\n' 70 "C:/llama-npu/rodada2/logs/r8-g1-nhvx8.jsonl"
sessao "G2-mmselect2"   'GGML_HEXAGON_MM_SELECT=2\n' 70 "C:/llama-npu/rodada2/logs/r8-g2-mm2.jsonl"
sessao "C-padrao"       "" 70 "C:/llama-npu/rodada2/logs/r8-c-padrao.jsonl"

"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt" >/dev/null 2>&1
echo "R8_PREINIT_DONE" | tee -a "$OUT"
