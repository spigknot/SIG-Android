#!/bin/bash
# r11-perf: perfil do prefill HTP (PROFILE=1 + log-buffer fora da trava)
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
S="100.108.27.64:5555"
L="C:/llama-npu/rodada2/logs/npuprobe-r11.log"
OUT="C:/llama-npu/rodada2/logs/r11-perf.txt"
PKG="br.gov.sp.pcsp.npuprobe"

echo "=== R11 PERF HTP ($(date -u +%H:%M:%SZ)) ===" | tee "$OUT"

roda() {  # $1=rotulo; $2=tune; $3=rotas; $4=sleep
  echo "=== [$1] tune='$2' ===" | tee -a "$OUT"
  if [ -z "$2" ]; then "$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt" >/dev/null 2>&1;
  else "$A" -s "$S" shell "printf '$2' > /data/local/tmp/p4-tune.txt" >/dev/null 2>&1; fi
  "$A" -s "$S" shell "printf '$3' > /data/local/tmp/p4-session.txt; echo smart:sessao > /data/local/tmp/p4-auto.txt; rm -f /data/local/tmp/p4-mutante.txt" >/dev/null 2>&1
  "$A" -s "$S" shell "am force-stop $PKG" >/dev/null 2>&1
  sleep 2
  L0=$(wc -l < "$L" 2>/dev/null || echo 0)
  "$A" -s "$S" shell "am start -n $PKG/.MainActivity" >/dev/null 2>&1
  sleep "$4"
  tail -n +$((L0+1)) "$L" 2>/dev/null | grep -aE "TUNE:|CHUNK\\[|profile-op|profile OPBATCH|Profiling|SESS\\[.*htp.*prefill" | sed 's/.*NpuProbe([0-9 ]*): /    /' | head -n 30 | tee -a "$OUT"
  echo "    ([ggml] linhas na janela: $(tail -n +$((L0+1)) "$L" 2>/dev/null | grep -ac '\\[ggml\\]'))" | tee -a "$OUT"
}

roda "P1-profile1"  'GGML_HEXAGON_PROFILE=1\n'  'puro:htp\npuro:htp\n' 55
roda "P2-padrao"    ""  'puro:htp\npuro:htp\n' 50
"$A" -s "$S" shell "rm -f /data/local/tmp/p4-tune.txt /data/local/tmp/p4-session.txt /data/local/tmp/p4-auto.txt" >/dev/null 2>&1
echo "R11_PERF_DONE" | tee -a "$OUT"
